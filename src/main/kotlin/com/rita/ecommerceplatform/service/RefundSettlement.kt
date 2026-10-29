package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.model.InventoryMovementType
import com.rita.ecommerceplatform.model.NotificationType
import com.rita.ecommerceplatform.model.Order
import com.rita.ecommerceplatform.model.OrderPaymentStatus
import com.rita.ecommerceplatform.model.OrderStatus
import com.rita.ecommerceplatform.model.Refund
import com.rita.ecommerceplatform.model.RefundStatus
import com.rita.ecommerceplatform.repository.CustomerRepository
import com.rita.ecommerceplatform.repository.InventoryMovementRepository
import com.rita.ecommerceplatform.repository.OrderRepository
import com.rita.ecommerceplatform.repository.OrderStatusHistoryRepository
import com.rita.ecommerceplatform.repository.ProductRepository
import com.rita.ecommerceplatform.repository.RefundProviderDetails
import com.rita.ecommerceplatform.repository.RefundRepository
import com.rita.ecommerceplatform.repository.api.GatewayRefund
import com.rita.ecommerceplatform.repository.api.GatewayRefundStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Applies what the provider says about a refund, exactly once. Shared by the refund start, refresh and the
 * refund webhook. Its own bean so that @Transactional works (like PaymentSettlement).
 * Locks are taken order → refund → products (by productId), the same order as the other order changes.
 */
@Service
class RefundSettlement(
    private val refundRepository: RefundRepository,
    private val orderRepository: OrderRepository,
    private val productRepository: ProductRepository,
    private val inventoryMovementRepository: InventoryMovementRepository,
    private val statusHistoryRepository: OrderStatusHistoryRepository,
    private val customerRepository: CustomerRepository,
    private val notificationService: NotificationService,
) {

    private val log = LoggerFactory.getLogger(RefundSettlement::class.java)

    /**
     * Settles a refund with the provider's answer and returns it as now stored. Whoever comes second finds it
     * no longer PENDING and changes nothing, so the order, the stock and the email are each changed once.
     */
    @Transactional
    fun settle(refundId: Long, result: GatewayRefund): Refund {
        // Read without a lock only to learn the order; a refund's order never changes.
        val orderId = refundRepository.findById(refundId)?.orderId ?: throw IllegalStateException("Refund $refundId does not exist")
        val order = orderRepository.lockById(orderId)!!
        val refund = refundRepository.lockById(refundId)!!
        if (refund.status != RefundStatus.PENDING) return refund

        val details = RefundProviderDetails(result.refundId, result.providerStatus, result.rawResponse)
        when (result.status) {
            GatewayRefundStatus.PENDING -> refundRepository.saveProviderDetails(refundId, details)
            GatewayRefundStatus.FAILED -> {
                val reason = result.failureReason ?: "The payment provider could not complete the refund (${result.providerStatus})"
                log.warn("Refund {} of order {} failed at the provider: {}", refundId, order.reference, reason)
                refundRepository.markFailed(refundId, reason, details)
            }
            GatewayRefundStatus.PROCESSED -> process(order, refund, details)
        }
        return refundRepository.findById(refundId)!!
    }

    private fun process(order: Order, refund: Refund, details: RefundProviderDetails) {
        check(refundRepository.markProcessed(refund.refundId, details) == 1) { "Refund ${refund.refundId} changed while locked" }

        // Checked when the refund started; checked again because the money is now gone whatever we decide.
        if (order.paymentStatus != OrderPaymentStatus.PAID ||
            (order.status != OrderStatus.CANCELLED && order.status != OrderStatus.DELIVERED)
        ) {
            log.error("Refund {} was processed but order {} is {} / {}; order left unchanged", refund.refundId, order.reference, order.status, order.paymentStatus)
            return
        }

        // A delivered order becomes REFUNDED; a cancelled one stays CANCELLED (it is final) and only its payment changes.
        val to = if (order.status == OrderStatus.DELIVERED) OrderStatus.REFUNDED else OrderStatus.CANCELLED
        if (to != order.status) OrderStatusRules.ensureCanChange(order.status, to, OrderStatusActor.ADMIN)
        check(orderRepository.markRefunded(order.orderId, order.status, to) == 1) { "Order ${order.reference} changed while locked" }
        if (refund.restock) returnStock(order, refund)

        // The admin who asked for the refund made the decision, whichever path (refresh or webhook) settles it.
        statusHistoryRepository.insert(
            orderId = order.orderId,
            fromStatus = order.status,
            toStatus = to,
            adminId = refund.requestedByAdminId,
            customerId = null,
            note = "$REFUND_PROCESSED: ${NotificationMessages.naira(refund.amount)}" + if (refund.restock) ". $ITEMS_RETURNED" else "",
        )

        val customer = customerRepository.findById(order.customerId)
            ?: throw IllegalStateException("Customer ${order.customerId} of order ${order.reference} does not exist")
        notificationService.queueToCustomer(
            NotificationType.ORDER_REFUNDED,
            customer,
            NotificationMessages.orderRefunded(customer.firstName, order.reference, refund.amount),
            orderId = order.orderId,
        )
    }

    // Puts back what the payment took (its SALE movements, by productId), exactly once: the refund is locked and
    // PROCESSED is final, and uq__ec_inventorymovement__order_refund rejects a second return.
    private fun returnStock(order: Order, refund: Refund) {
        inventoryMovementRepository.findSalesByOrder(order.orderId).forEach { sale ->
            val stockAfter = productRepository.increaseStock(sale.productId, sale.quantity)
                ?: throw IllegalStateException("Product ${sale.productId} of order ${order.reference} does not exist")
            inventoryMovementRepository.insert(
                productId = sale.productId,
                type = InventoryMovementType.REFUND_RETURN,
                quantityChange = sale.quantity,
                stockAfter = stockAfter,
                adminId = refund.requestedByAdminId,
                note = "Order ${order.reference} returned and refunded",
                orderId = order.orderId,
            )
        }
    }

    companion object {
        const val REFUND_PROCESSED = "Refund processed"
        const val ITEMS_RETURNED = "Items returned to stock"
    }
}
