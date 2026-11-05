package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.entity.InventoryMovementType
import com.rita.ecommerceplatform.model.entity.NotificationType
import com.rita.ecommerceplatform.model.entity.Order
import com.rita.ecommerceplatform.model.entity.OrderPaymentStatus
import com.rita.ecommerceplatform.model.entity.OrderStatus
import com.rita.ecommerceplatform.model.entity.PaymentStatus
import com.rita.ecommerceplatform.repository.database.CouponRepository
import com.rita.ecommerceplatform.repository.database.CustomerRepository
import com.rita.ecommerceplatform.repository.database.InventoryMovementRepository
import com.rita.ecommerceplatform.repository.database.OrderRepository
import com.rita.ecommerceplatform.repository.database.OrderStatusHistoryRepository
import com.rita.ecommerceplatform.repository.database.PaymentRepository
import com.rita.ecommerceplatform.repository.database.ProductRepository
import com.rita.ecommerceplatform.repository.api.PaymentGateway
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Cancels orders, each in one transaction. Its own bean so that @Transactional works when the services call it
 * after settleOpenPayment (the provider call must run outside any transaction).
 * Locks are taken order → payment → products (by productId), the same order as PaymentSettlement, so they never deadlock;
 * an unpaid order's coupon is locked last, and checkout locks customer → coupon, so the two never wait on each other.
 */
@Service
class OrderCancellation(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val paymentGateway: PaymentGateway,
    private val paymentSettlement: PaymentSettlement,
    private val productRepository: ProductRepository,
    private val inventoryMovementRepository: InventoryMovementRepository,
    private val statusHistoryRepository: OrderStatusHistoryRepository,
    private val customerRepository: CustomerRepository,
    private val notificationService: NotificationService,
    private val couponRepository: CouponRepository,
) {

    /**
     * Call outside any transaction, before cancelling an unpaid order. If the order has an open payment page, asks
     * the provider about it and settles it (like verify). Returns true if it was paid: the order is then CONFIRMED.
     * Throws 502 if the provider cannot be reached, so nothing is cancelled blindly.
     */
    fun settleOpenPayment(orderId: Long): Boolean {
        val open = paymentRepository.findOpenAttempt(orderId)?.takeIf { it.authorizationUrl != null } ?: return false
        return paymentSettlement.settle(open.paymentId, paymentGateway.verify(open.reference)).status == PaymentStatus.SUCCESS
    }

    /**
     * The customer cancels their own PENDING, UNPAID order. No stock was taken yet (it is taken on payment),
     * so there is nothing to put back. The open payment attempt, if any, is closed so it can no longer settle the order.
     */
    @Transactional
    fun cancelByCustomer(customerId: Long, orderId: Long, reason: String?) {
        val order = orderRepository.lockById(orderId)
        if (order == null || order.customerId != customerId) throw NotFoundException("Order not found")
        // Checked again under the lock: a payment may have settled since OrderService looked.
        if (order.status != OrderStatus.PENDING || order.paymentStatus != OrderPaymentStatus.UNPAID) throw notCancellable()
        OrderStatusRules.ensureCanChange(order.status, OrderStatus.CANCELLED, OrderStatusActor.CUSTOMER)

        closeOpenAttempt(orderId, PAYMENT_CLOSED)
        moveToCancelled(order)
        releaseCoupon(order)
        statusHistoryRepository.insert(
            orderId = orderId,
            fromStatus = OrderStatus.PENDING,
            toStatus = OrderStatus.CANCELLED,
            adminId = null,
            customerId = customerId,
            note = if (reason == null) CANCELLED_BY_CUSTOMER else "$CANCELLED_BY_CUSTOMER: $reason",
        )

        val customer = customerRepository.findById(customerId)
            ?: throw IllegalStateException("Customer $customerId of order ${order.reference} does not exist")
        notificationService.queueToCustomer(
            NotificationType.ORDER_CANCELLED,
            customer,
            NotificationMessages.orderCancelledByCustomer(customer.firstName, order.reference),
            orderId = orderId,
        )
    }

    /**
     * An admin cancels an order that has not shipped: PENDING (unpaid) or CONFIRMED / PROCESSING (paid).
     * What the payment took from stock goes back, exactly once: the order is locked, CANCELLED is final, and
     * uq__ec_inventorymovement__order_cancel rejects a second restore. A paid order stays PAID: a refund is needed.
     */
    @Transactional
    fun cancelByAdmin(adminId: Int, orderId: Long, reason: String) {
        val order = orderRepository.lockById(orderId) ?: throw NotFoundException("Order not found")
        if (order.status == OrderStatus.SHIPPED || order.status == OrderStatus.DELIVERED) {
            throw ConflictException("Shipped or delivered orders cannot be cancelled; use a refund instead")
        }
        OrderStatusRules.ensureCanChange(order.status, OrderStatus.CANCELLED, OrderStatusActor.ADMIN)

        if (order.status == OrderStatus.PENDING) closeOpenAttempt(orderId, PAYMENT_CLOSED_BY_ADMIN)
        moveToCancelled(order)
        restoreStock(order, adminId)
        releaseCoupon(order)

        val paid = order.paymentStatus == OrderPaymentStatus.PAID
        statusHistoryRepository.insert(
            orderId = orderId,
            fromStatus = order.status,
            toStatus = OrderStatus.CANCELLED,
            adminId = adminId,
            customerId = null,
            note = "$CANCELLED_BY_ADMIN: $reason" + if (paid) ". $REFUND_NEEDED" else "",
        )

        val customer = customerRepository.findById(order.customerId)
            ?: throw IllegalStateException("Customer ${order.customerId} of order ${order.reference} does not exist")
        notificationService.queueToCustomer(
            NotificationType.ORDER_CANCELLED,
            customer,
            NotificationMessages.orderCancelled(customer.firstName, order, reason, paid),
            orderId = orderId,
        )
    }

    // An attempt still without a page is either crashed (marked here) or being started right now: its page
    // would reach the customer after the cancel, so wait for it instead.
    private fun closeOpenAttempt(orderId: Long, note: String) {
        paymentRepository.failStaleStarts(orderId, PaymentService.STALE_START_SECONDS, PaymentService.NOT_STARTED)
        paymentRepository.findOpenAttempt(orderId)?.let { open ->
            if (open.authorizationUrl == null) {
                throw ConflictException("A payment for this order is being started, try again in a moment")
            }
            paymentRepository.markFailed(open.paymentId, note)
        }
    }

    private fun moveToCancelled(order: Order) {
        check(orderRepository.updateStatus(order.orderId, order.status, OrderStatus.CANCELLED) == 1) {
            "Order ${order.orderId} changed while locked"
        }
    }

    // An unpaid order gives its coupon use back, exactly once: the order is locked and CANCELLED is final.
    // A paid order keeps it (the coupon was redeemed; a refund returns what was paid). Lock order: order → coupon.
    private fun releaseCoupon(order: Order) {
        val couponId = order.couponId ?: return
        if (order.paymentStatus != OrderPaymentStatus.UNPAID) return
        check(couponRepository.release(couponId) == 1) { "Coupon $couponId of order ${order.reference} has no use to give back" }
    }

    // Puts back exactly what the payment took (its SALE movements, by productId), so an order that never took
    // stock (unpaid, or cancelled because stock ran out) puts nothing back.
    private fun restoreStock(order: Order, adminId: Int) {
        inventoryMovementRepository.findSalesByOrder(order.orderId).forEach { sale ->
            val stockAfter = productRepository.increaseStock(sale.productId, sale.quantity)
                ?: throw IllegalStateException("Product ${sale.productId} of order ${order.reference} does not exist")
            inventoryMovementRepository.insert(
                productId = sale.productId,
                type = InventoryMovementType.ORDER_CANCELLED,
                quantityChange = sale.quantity,
                stockAfter = stockAfter,
                adminId = adminId,
                note = "Order ${order.reference} cancelled",
                orderId = order.orderId,
            )
        }
    }

    companion object {
        const val CANCELLED_BY_CUSTOMER = "Cancelled by customer"
        const val CANCELLED_BY_ADMIN = "Cancelled by admin"
        const val REFUND_NEEDED = "Refund needed"
        const val PAYMENT_CLOSED = "Closed: order cancelled by the customer"
        const val PAYMENT_CLOSED_BY_ADMIN = "Closed: order cancelled by an admin"

        fun notCancellable() = ConflictException("Only unpaid pending orders can be cancelled")
    }
}
