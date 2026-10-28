package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.NotificationType
import com.rita.ecommerceplatform.model.OrderPaymentStatus
import com.rita.ecommerceplatform.model.OrderStatus
import com.rita.ecommerceplatform.repository.CustomerRepository
import com.rita.ecommerceplatform.repository.OrderRepository
import com.rita.ecommerceplatform.repository.OrderStatusHistoryRepository
import com.rita.ecommerceplatform.repository.PaymentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Cancels an order, in one transaction. Its own bean so that @Transactional works when OrderService calls it
 * after asking the provider about the open payment (that call must run outside any transaction).
 * Locks are taken order → payment, the same order as PaymentSettlement, so the two never deadlock.
 */
@Service
class OrderCancellation(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val statusHistoryRepository: OrderStatusHistoryRepository,
    private val customerRepository: CustomerRepository,
    private val notificationService: NotificationService,
) {

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

        // An attempt still without a page is either crashed (marked here) or being started right now: its page
        // would reach the customer after the cancel, so wait for it instead.
        paymentRepository.failStaleStarts(orderId, PaymentService.STALE_START_SECONDS, PaymentService.NOT_STARTED)
        paymentRepository.findOpenAttempt(orderId)?.let { open ->
            if (open.authorizationUrl == null) {
                throw ConflictException("A payment for this order is being started, try again in a moment")
            }
            paymentRepository.markFailed(open.paymentId, PAYMENT_CLOSED)
        }

        check(orderRepository.updateStatus(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED) == 1) {
            "Order $orderId changed while locked"
        }
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

    companion object {
        const val CANCELLED_BY_CUSTOMER = "Cancelled by customer"
        const val PAYMENT_CLOSED = "Closed: order cancelled by the customer"

        fun notCancellable() = ConflictException("Only unpaid pending orders can be cancelled")
    }
}
