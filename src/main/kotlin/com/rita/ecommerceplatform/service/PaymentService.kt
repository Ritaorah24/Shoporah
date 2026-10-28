package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.dto.response.PaymentInitResponse
import com.rita.ecommerceplatform.dto.response.PaymentVerifyResponse
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.InvalidCredentialsException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.Order
import com.rita.ecommerceplatform.model.OrderPaymentStatus
import com.rita.ecommerceplatform.model.OrderStatus
import com.rita.ecommerceplatform.model.Payment
import com.rita.ecommerceplatform.model.PaymentStatus
import com.rita.ecommerceplatform.repository.CustomerRepository
import com.rita.ecommerceplatform.repository.OrderRepository
import com.rita.ecommerceplatform.repository.PaymentRepository
import com.rita.ecommerceplatform.repository.api.GatewayInitRequest
import com.rita.ecommerceplatform.repository.api.PaymentGateway
import com.rita.ecommerceplatform.repository.api.paystack.PaystackWebhookEvent
import com.rita.ecommerceplatform.repository.api.paystack.PaystackWebhookVerifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

/** The payment and whether this request started it (201) or found an attempt already open for the order (200). */
data class PaymentInitResult(val payment: PaymentInitResponse, val created: Boolean)

@Service
class PaymentService(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val customerRepository: CustomerRepository,
    private val paymentGateway: PaymentGateway,
    private val paymentSettlement: PaymentSettlement,
    private val webhookVerifier: PaystackWebhookVerifier,
    private val jsonMapper: JsonMapper,
    // Transactions are opened in code, not with @Transactional: the Paystack call in the middle of
    // initialize must run outside any transaction, and @Transactional on a method called from inside
    // this same class would be ignored (Spring applies it through a proxy that such calls bypass).
    private val transactionTemplate: TransactionTemplate,
    @Value("\${app.paystack.callback-url}") private val callbackUrl: String,
) {

    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    /**
     * Starts paying for one of the customer's PENDING, UNPAID orders and returns the provider's payment page.
     * The amount and currency always come from the order. If the order already has an open attempt with a
     * page, that attempt is returned instead, so a double-click never opens two payment pages (two charges).
     */
    fun initialize(customerId: Long, orderReference: String): PaymentInitResult {
        val start = transactionTemplate.execute { startAttempt(customerId, orderReference) }!!
        if (!start.isNew) return PaymentInitResult(PaymentInitResponse.from(start.payment, start.order.reference), created = false)

        // Outside any transaction: Paystack can take up to 10 s, and no lock is held meanwhile.
        val page = try {
            paymentGateway.initialize(
                GatewayInitRequest(
                    reference = start.payment.reference,
                    email = start.email!!,
                    amountKobo = toKobo(start.payment.amount),
                    currency = start.payment.currency,
                    callbackUrl = callbackUrl.ifBlank { null },
                ),
            )
        } catch (e: Exception) {
            // One statement, so it commits on its own. The order can then be paid with a new attempt.
            paymentRepository.markFailed(start.payment.paymentId, NOT_STARTED)
            throw e
        }

        paymentRepository.saveAuthorizationUrl(start.payment.paymentId, page.authorizationUrl)
        val payment = paymentRepository.findById(start.payment.paymentId)!!
        return PaymentInitResult(PaymentInitResponse.from(payment, start.order.reference), created = true)
    }

    /**
     * Asks the provider what happened to one of the customer's payments and settles it (PaymentSettlement).
     * The client only names the payment; whether it was paid always comes from the provider. A payment that is
     * already settled is returned as stored, without asking the provider again.
     */
    fun verify(customerId: Long, paymentReference: String): PaymentVerifyResponse {
        val payment = paymentRepository.findByReferenceForCustomer(paymentReference, customerId)
            ?: throw NotFoundException("Payment not found")
        val settled = if (payment.status != PaymentStatus.PENDING) {
            payment
        } else {
            // Outside any transaction, like initialize: no lock is held while the provider answers.
            val result = paymentGateway.verify(payment.reference)
            paymentSettlement.settle(payment.paymentId, result)
        }
        val order = orderRepository.findById(settled.orderId, customerId)!!
        return PaymentVerifyResponse.from(settled, order)
    }

    /**
     * Handles a webhook from Paystack, which reaches us even if the customer closed the browser after paying.
     * The body is only a signal: after the signature check we ask Paystack about the payment ourselves and
     * settle it exactly like verify does, so a webhook, a repeated webhook and a verify can never settle twice.
     * Returning normally means 200 (Paystack stops sending it); an exception means Paystack retries later.
     */
    fun handleWebhook(body: ByteArray, signature: String?) {
        if (!webhookVerifier.isValid(body, signature)) {
            log.warn("Rejected a Paystack webhook with a missing or wrong signature")
            throw InvalidCredentialsException("Invalid webhook signature")
        }
        val event = PaystackWebhookEvent.parse(jsonMapper, body) ?: run {
            log.error("Paystack webhook has a valid signature but an unreadable body: {}", body.toString(Charsets.UTF_8).take(500))
            throw BadRequestException("Unreadable webhook body")
        }
        if (!event.isChargeSuccess || event.reference == null) {
            log.info("Ignored Paystack webhook {} for {}", event.event, event.reference)
            return
        }

        // Not ours (e.g. another app on the same Paystack account): retrying would not help, so it is not an error.
        val payment = paymentRepository.findByReference(event.reference) ?: run {
            log.warn("Paystack webhook {} for unknown reference {}", event.event, event.reference)
            return
        }
        if (payment.status != PaymentStatus.PENDING) return

        // Outside any transaction, like verify. If Paystack cannot be reached this is a 502 and Paystack retries.
        val result = paymentGateway.verify(payment.reference)
        val settled = paymentSettlement.settle(payment.paymentId, result)
        log.info("Paystack webhook {} settled payment {} as {}", event.event, settled.reference, settled.status)
    }

    /** Runs in one short transaction: checks the order and returns its open attempt or a new PENDING one. */
    private fun startAttempt(customerId: Long, orderReference: String): Start {
        // Payment starts for the same order run one at a time; another customer's order is "not found" too.
        val order = orderRepository.lockByReference(orderReference, customerId) ?: throw NotFoundException("Order not found")
        if (order.status != OrderStatus.PENDING || order.paymentStatus != OrderPaymentStatus.UNPAID) {
            throw ConflictException("This order cannot be paid")
        }

        // An attempt still without a page after STALE_START_SECONDS was left by a crashed request.
        paymentRepository.failStaleStarts(order.orderId, STALE_START_SECONDS, NOT_STARTED)
        paymentRepository.findOpenAttempt(order.orderId)?.let { open ->
            if (open.authorizationUrl != null) return Start(order, open, email = null, isNew = false)
            // Another request is waiting for Paystack right now; it will return the page.
            throw ConflictException("A payment for this order is already being started, try again in a moment")
        }

        val email = customerRepository.findById(customerId)?.email ?: throw NotFoundException("Order not found")
        val paymentId = paymentRepository.insert(order.orderId, newReference(), PROVIDER, order.total, CURRENCY)
        return Start(order, paymentRepository.findById(paymentId)!!, email, isNew = true)
    }

    private data class Start(val order: Order, val payment: Payment, val email: String?, val isNew: Boolean)

    companion object {
        const val PROVIDER = "PAYSTACK"
        // Orders have no currency column: the store sells in naira only.
        const val CURRENCY = "NGN"
        // Longer than PaystackConfig's 5 s connect + 10 s read timeouts, so a live request is never cut off.
        const val STALE_START_SECONDS = 30
        const val NOT_STARTED ="Not started: the payment provider could not be reached"

        // A random UUID, so a reference can never be guessed or clash in practice (uq__ec_payment__reference).
        fun newReference(): String = "PAY-${UUID.randomUUID().toString().uppercase()}"

        /** Naira to kobo, exactly: 12500.50 becomes 1250050. Throws if the amount has fractions of a kobo. */
        fun toKobo(amount: BigDecimal): Long = amount.movePointRight(2).longValueExact()
    }
}
