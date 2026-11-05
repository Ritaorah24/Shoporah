package com.rita.ecommerceplatform.service

import io.github.oshai.kotlinlogging.KotlinLogging
import com.rita.ecommerceplatform.exception.ApiErrorException
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.UnableToLocateRecordException
import com.rita.ecommerceplatform.model.request.RefundOrderRequest
import com.rita.ecommerceplatform.model.response.AdminOrderResponse
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
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

private val logger = KotlinLogging.logger {}

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
                    transactionReference = payment.paymentReference!!,
                    amountKobo = PaymentService.toKobo(refund.refundAmount!!),
                    currency = refund.refundCurrency!!,
                    merchantNote = reason,
                ),
            )
        } catch (e: ApiErrorException) {
            logger.warn { "Refund ${refund.refundId} of order $orderId: the provider could not be reached; left PENDING until refreshed" }
            null
        }
        when (answer) {
            is GatewayRefundStart.Accepted -> refundSettlement.settle(refund.refundId!!, answer.refund)
            is GatewayRefundStart.Rejected -> refundRepository.markFailed(refund.refundId!!, answer.message)
            null -> Unit
        }
        return adminOrderService.get(orderId)
    }

    /**
     * Asks the provider about a PENDING refund and settles it (the refund's "verify"; until webhooks reach us this
     * is how a refund finishes). A settled refund is returned as stored, without asking the provider again.
     */
    fun refresh(refundId: Long): AdminOrderResponse {
        val refund = refundRepository.findById(refundId) ?: throw UnableToLocateRecordException("Refund not found")
        if (refund.refundStatus == RefundStatus.PENDING) {
            if (refund.refundProviderRefundId != null) {
                paymentGateway.fetchRefund(refund.refundProviderRefundId!!)?.let { refundSettlement.settle(refundId, it) }
                    ?: logger.warn { "Refund $refundId: the provider does not know refund id ${refund.refundProviderRefundId}" }
            } else {
                findUnconfirmed(refund)
            }
        }
        return adminOrderService.get(refund.refundOrderId!!)
    }

    /**
     * A signed refund webhook (checkpoint 2.2, Step 5b), called by PaymentService.handleWebhook after the signature
     * check. The body only says which refund to look at: what happened is asked of the provider again and settled
     * exactly like refresh, so a webhook, a repeated webhook and a refresh never settle twice.
     * Returning normally means 200; a 502 (provider unreachable) makes Paystack send it again later.
     */
    fun handleWebhook(event: PaystackWebhookEvent) {
        if (!event.isRefundFinal) {
            logger.info { "Ignored Paystack webhook ${event.event} for refund ${event.refundId}" }
            return
        }

        // Ours already knows this provider id: ask about it directly.
        val known = event.refundId?.let(refundRepository::findByProviderRefundId)
        if (known != null) {
            if (known.refundStatus != RefundStatus.PENDING) return
            val result = paymentGateway.fetchRefund(known.refundProviderRefundId!!) ?: run {
                logger.warn { "Paystack webhook ${event.event}: the provider does not know refund id ${known.refundProviderRefundId}" }
                return
            }
            val settled = refundSettlement.settle(known.refundId!!, result)
            logger.info { "Paystack webhook ${event.event} settled refund ${settled.refundId} as ${settled.refundStatus}" }
            return
        }

        // A refund whose start never heard back: found through its payment, as refresh does. Anything else is not
        // ours (e.g. made in the Paystack dashboard); retrying would not help, so it is not an error.
        val unconfirmed = event.transactionReference
            ?.let(paymentRepository::findByReference)
            ?.let { refundRepository.findUnconfirmedForPayment(it.paymentId!!) }
            ?: run {
                logger.warn { "Paystack webhook ${event.event} for a refund we do not know: id ${event.refundId}, payment ${event.transactionReference}" }
                return
            }
        findUnconfirmed(unconfirmed)
        logger.info { "Paystack webhook ${event.event} checked unconfirmed refund ${unconfirmed.refundId}" }
    }

    // The start never heard back from the provider. A refund for this payment that none of our rows claims yet
    // is this one; if there is none after a grace period, nothing was sent and the refund can be tried again.
    private fun findUnconfirmed(refund: Refund) {
        val payment = paymentRepository.findById(refund.refundPaymentId!!)!!
        val claimed = refundRepository.findProviderRefundIds(payment.paymentId!!)
        val found = paymentGateway.findRefunds(payment.paymentProviderTransactionId, payment.paymentReference!!)
            .firstOrNull { it.refundId !in claimed && it.amountKobo == PaymentService.toKobo(refund.refundAmount!!) }
        if (found != null) {
            refundSettlement.settle(refund.refundId!!, found)
        } else if (refundRepository.failUnconfirmed(refund.refundId!!, UNCONFIRMED_GRACE_SECONDS, NOT_RECEIVED) == 1) {
            logger.warn { "Refund ${refund.refundId}: the provider has no record of it; marked FAILED" }
        }
    }

    /** Runs in one short transaction: checks the order can be refunded and records a PENDING refund. */
    private fun createRefund(adminId: Int, orderId: Long, reason: String, restock: Boolean): Pair<Refund, Payment> {
        // Refund starts and settles for one order run one at a time.
        val order = orderRepository.lockById(orderId) ?: throw UnableToLocateRecordException("Order not found")
        ensureRefundable(order)
        if (restock && order.orderStatus != OrderStatus.DELIVERED) {
            throw BadRequestException("restock is only for delivered orders: a cancelled order's stock was already put back")
        }
        val payment = paymentRepository.findSuccessfulForOrder(orderId)
            ?: throw IllegalStateException("Order ${order.orderReference} is PAID but has no successful payment")
        if (refundRepository.findByOrder(orderId).any { it.refundStatus != RefundStatus.FAILED }) throw refundInProgress()

        val refundId = try {
            refundRepository.insert(orderId, payment.paymentId!!, order.orderTotal!!, payment.paymentCurrency!!, reason, restock, adminId)
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
            order.orderPaymentStatus == OrderPaymentStatus.PAID &&
                (order.orderStatus == OrderStatus.CANCELLED || order.orderStatus == OrderStatus.DELIVERED)

        private fun ensureRefundable(order: Order) {
            if (order.orderPaymentStatus == OrderPaymentStatus.REFUNDED) throw BadRequestException("This order has already been refunded")
            if (order.orderPaymentStatus != OrderPaymentStatus.PAID) throw BadRequestException("Only paid orders can be refunded")
            if (!isRefundable(order)) {
                throw BadRequestException("Only cancelled or delivered orders can be refunded; cancel the order first if it has not shipped")
            }
        }

        private fun refundInProgress() = BadRequestException("A refund for this order is already in progress")
    }
}
