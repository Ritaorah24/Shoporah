package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.model.request.RefundOrderRequest
import com.rita.ecommerceplatform.model.response.AdminOrderResponse
import com.rita.ecommerceplatform.exception.BadGatewayException
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.entity.Order
import com.rita.ecommerceplatform.model.entity.OrderPaymentStatus
import com.rita.ecommerceplatform.model.entity.OrderStatus
import com.rita.ecommerceplatform.model.entity.Payment
import com.rita.ecommerceplatform.model.entity.Refund
import com.rita.ecommerceplatform.model.entity.RefundStatus
import com.rita.ecommerceplatform.repository.database.OrderRepository
import com.rita.ecommerceplatform.repository.database.PaymentRepository
import com.rita.ecommerceplatform.repository.database.RefundRepository
import com.rita.ecommerceplatform.repository.api.GatewayRefundRequest
import com.rita.ecommerceplatform.repository.api.GatewayRefundStart
import com.rita.ecommerceplatform.repository.api.PaymentGateway
import com.rita.ecommerceplatform.model.request.paystack.PaystackWebhookEvent
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Admin refunds through the payment provider (checkpoint 2.2, Step 5). Only whole orders are refunded: the
 * order total, back to the order's successful payment. Refunds finish later at the provider, so a refund
 * starts PENDING and RefundSettlement applies the outcome once (on start, refresh, or the refund webhook).
 */
@Service
class RefundService(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val refundRepository: RefundRepository,
    private val paymentGateway: PaymentGateway,
    private val refundSettlement: RefundSettlement,
    private val adminOrderService: AdminOrderService,
    // Transactions are opened in code, like PaymentService: the provider call must run outside any transaction.
    private val transactionTemplate: TransactionTemplate,
) {

    private val log = LoggerFactory.getLogger(RefundService::class.java)

    /**
     * Starts refunding a paid order that is CANCELLED or DELIVERED. If the provider refuses, the refund is FAILED
     * with its message and can be tried again; if it cannot be reached, the refund stays PENDING without a
     * provider id, and refresh asks the provider whether it was created (it is never sent a second time blindly).
     */
    fun start(adminId: Int, orderId: Long, request: RefundOrderRequest): AdminOrderResponse {
        val reason = request.reason!!.trim()
        val (refund, payment) = transactionTemplate.execute { createRefund(adminId, orderId, reason, request.restock) }!!

        // Outside any transaction: no lock is held while the provider answers.
        val answer = try {
            paymentGateway.refund(
                GatewayRefundRequest(
                    transactionReference = payment.reference,
                    amountKobo = PaymentService.toKobo(refund.amount),
                    currency = refund.currency,
                    merchantNote = reason,
                ),
            )
        } catch (e: BadGatewayException) {
            log.warn("Refund {} of order {}: the provider could not be reached; left PENDING until refreshed", refund.refundId, orderId)
            null
        }
        when (answer) {
            is GatewayRefundStart.Accepted -> refundSettlement.settle(refund.refundId, answer.refund)
            is GatewayRefundStart.Rejected -> refundRepository.markFailed(refund.refundId, answer.message)
            null -> Unit
        }
        return adminOrderService.get(orderId)
    }

    /**
     * Asks the provider about a PENDING refund and settles it (the refund's "verify"; until webhooks reach us this
     * is how a refund finishes). A settled refund is returned as stored, without asking the provider again.
     */
    fun refresh(refundId: Long): AdminOrderResponse {
        val refund = refundRepository.findById(refundId) ?: throw NotFoundException("Refund not found")
        if (refund.status == RefundStatus.PENDING) {
            if (refund.providerRefundId != null) {
                paymentGateway.fetchRefund(refund.providerRefundId)?.let { refundSettlement.settle(refundId, it) }
                    ?: log.warn("Refund {}: the provider does not know refund id {}", refundId, refund.providerRefundId)
            } else {
                findUnconfirmed(refund)
            }
        }
        return adminOrderService.get(refund.orderId)
    }

    /**
     * A signed refund webhook (checkpoint 2.2, Step 5b), called by PaymentService.handleWebhook after the signature
     * check. The body only says which refund to look at: what happened is asked of the provider again and settled
     * exactly like refresh, so a webhook, a repeated webhook and a refresh never settle twice.
     * Returning normally means 200; a 502 (provider unreachable) makes Paystack send it again later.
     */
    fun handleWebhook(event: PaystackWebhookEvent) {
        if (!event.isRefundFinal) {
            log.info("Ignored Paystack webhook {} for refund {}", event.event, event.refundId)
            return
        }

        // Ours already knows this provider id: ask about it directly.
        val known = event.refundId?.let(refundRepository::findByProviderRefundId)
        if (known != null) {
            if (known.status != RefundStatus.PENDING) return
            val result = paymentGateway.fetchRefund(known.providerRefundId!!) ?: run {
                log.warn("Paystack webhook {}: the provider does not know refund id {}", event.event, known.providerRefundId)
                return
            }
            val settled = refundSettlement.settle(known.refundId, result)
            log.info("Paystack webhook {} settled refund {} as {}", event.event, settled.refundId, settled.status)
            return
        }

        // A refund whose start never heard back: found through its payment, as refresh does. Anything else is not
        // ours (e.g. made in the Paystack dashboard); retrying would not help, so it is not an error.
        val unconfirmed = event.transactionReference
            ?.let(paymentRepository::findByReference)
            ?.let { refundRepository.findUnconfirmedForPayment(it.paymentId) }
            ?: run {
                log.warn(
                    "Paystack webhook {} for a refund we do not know: id {}, payment {}",
                    event.event, event.refundId, event.transactionReference,
                )
                return
            }
        findUnconfirmed(unconfirmed)
        log.info("Paystack webhook {} checked unconfirmed refund {}", event.event, unconfirmed.refundId)
    }

    // The start never heard back from the provider. A refund for this payment that none of our rows claims yet
    // is this one; if there is none after a grace period, nothing was sent and the refund can be tried again.
    private fun findUnconfirmed(refund: Refund) {
        val payment = paymentRepository.findById(refund.paymentId)!!
        val claimed = refundRepository.findProviderRefundIds(payment.paymentId)
        val found = paymentGateway.findRefunds(payment.providerTransactionId, payment.reference)
            .firstOrNull { it.refundId !in claimed && it.amountKobo == PaymentService.toKobo(refund.amount) }
        if (found != null) {
            refundSettlement.settle(refund.refundId, found)
        } else if (refundRepository.failUnconfirmed(refund.refundId, UNCONFIRMED_GRACE_SECONDS, NOT_RECEIVED) == 1) {
            log.warn("Refund {}: the provider has no record of it; marked FAILED", refund.refundId)
        }
    }

    /** Runs in one short transaction: checks the order can be refunded and records a PENDING refund. */
    private fun createRefund(adminId: Int, orderId: Long, reason: String, restock: Boolean): Pair<Refund, Payment> {
        // Refund starts and settles for one order run one at a time.
        val order = orderRepository.lockById(orderId) ?: throw NotFoundException("Order not found")
        ensureRefundable(order)
        if (restock && order.status != OrderStatus.DELIVERED) {
            throw BadRequestException("restock is only for delivered orders: a cancelled order's stock was already put back")
        }
        val payment = paymentRepository.findSuccessfulForOrder(orderId)
            ?: throw IllegalStateException("Order ${order.reference} is PAID but has no successful payment")
        if (refundRepository.findByOrder(orderId).any { it.status != RefundStatus.FAILED }) throw refundInProgress()

        val refundId = try {
            refundRepository.insert(orderId, payment.paymentId, order.total, payment.currency, reason, restock, adminId)
        } catch (e: DuplicateKeyException) {
            // uq__ec_refund__payment_active: the database's own guard against a second refund.
            throw refundInProgress()
        }
        return refundRepository.findById(refundId)!! to payment
    }

    companion object {
        // A start that crashed or never reached the provider; after this long the provider would know about it.
        const val UNCONFIRMED_GRACE_SECONDS = 60
        const val NOT_RECEIVED = "The payment provider has no record of this refund; nothing was refunded, you can try again"

        /** Whether an order can be refunded, ignoring refunds in progress (AdminOrderService adds that for canRefund). */
        fun isRefundable(order: Order): Boolean =
            order.paymentStatus == OrderPaymentStatus.PAID &&
                (order.status == OrderStatus.CANCELLED || order.status == OrderStatus.DELIVERED)

        private fun ensureRefundable(order: Order) {
            if (order.paymentStatus == OrderPaymentStatus.REFUNDED) throw ConflictException("This order has already been refunded")
            if (order.paymentStatus != OrderPaymentStatus.PAID) throw ConflictException("Only paid orders can be refunded")
            if (!isRefundable(order)) {
                throw ConflictException("Only cancelled or delivered orders can be refunded; cancel the order first if it has not shipped")
            }
        }

        private fun refundInProgress() = ConflictException("A refund for this order is already in progress")
    }
}
