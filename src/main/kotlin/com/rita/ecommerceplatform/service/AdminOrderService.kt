package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.dto.request.AdminCancelOrderRequest
import com.rita.ecommerceplatform.dto.request.UpdateOrderStatusRequest
import com.rita.ecommerceplatform.dto.response.AdminOrderResponse
import com.rita.ecommerceplatform.dto.response.AdminOrderSummaryResponse
import com.rita.ecommerceplatform.dto.response.PageResponse
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.NotificationType
import com.rita.ecommerceplatform.model.Order
import com.rita.ecommerceplatform.model.OrderStatus
import com.rita.ecommerceplatform.repository.CustomerRepository
import com.rita.ecommerceplatform.repository.DeliveryMethodRepository
import com.rita.ecommerceplatform.repository.OrderItemRepository
import com.rita.ecommerceplatform.repository.OrderRepository
import com.rita.ecommerceplatform.repository.OrderStatusHistoryRepository
import com.rita.ecommerceplatform.repository.OrderWithCustomer
import com.rita.ecommerceplatform.repository.PaymentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Orders as admins see and fulfil them: every customer's orders, unlike OrderService, which is customer-scoped. */
@Service
class AdminOrderService(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val statusHistoryRepository: OrderStatusHistoryRepository,
    private val paymentRepository: PaymentRepository,
    private val customerRepository: CustomerRepository,
    private val notificationService: NotificationService,
    private val deliveryMethodRepository: DeliveryMethodRepository,
    private val orderCancellation: OrderCancellation,
) {

    /** Every customer's orders, newest first; only one status when given. Paging is capped like the other lists (D6). */
    fun list(page: Int, size: Int, status: String?): PageResponse<AdminOrderSummaryResponse> {
        val statusFilter = parseStatus(status)
        val safePage = Paging.page(page)
        val safeSize = Paging.size(size)
        val rows = orderRepository.findAllWithCustomer(statusFilter, safeSize, Paging.offset(safePage, safeSize))
        // One grouped query for the whole page, not one per order.
        val quantities = orderItemRepository.quantitiesByOrderIds(rows.map { it.order.orderId })
        return PageResponse.of(
            rows.map { AdminOrderSummaryResponse.from(it, quantities[it.order.orderId] ?: 0) },
            safePage,
            safeSize,
            orderRepository.countAll(statusFilter),
        )
    }

    fun get(orderId: Long): AdminOrderResponse =
        toResponse(orderRepository.findByIdWithCustomer(orderId) ?: throw orderNotFound())

    /**
     * Moves the order to the next fulfilment status. The order row is locked first, so two admins changing
     * the same order take turns: the second sees the new status and is rejected by OrderStatusRules (409).
     */
    @Transactional
    fun updateStatus(adminId: Int, orderId: Long, request: UpdateOrderStatusRequest): AdminOrderResponse {
        val order = orderRepository.lockById(orderId) ?: throw orderNotFound()
        val to = OrderStatus.valueOf(request.status!!.uppercase())
        OrderStatusRules.ensureCanChange(order.status, to, OrderStatusActor.ADMIN)

        // The lock makes this always 1; the WHERE on the old status is a second guard.
        check(orderRepository.updateStatus(orderId, order.status, to) == 1) { "Order $orderId changed while locked" }
        val note = request.note?.trim()?.ifEmpty { null }
        statusHistoryRepository.insert(
            orderId = orderId,
            fromStatus = order.status,
            toStatus = to,
            adminId = adminId,
            customerId = null,
            note = note,
        )
        notifyCustomer(order, to, note)
        return get(orderId)
    }

    /**
     * Cancels an order that has not shipped (OrderCancellation.cancelByAdmin). For an unpaid order with an open
     * payment page, the provider is asked first: if it was just paid, the admin gets 409 and sees the order as it
     * is now (CONFIRMED, stock taken) before deciding again, instead of an unexpected refund case.
     */
    fun cancel(adminId: Int, orderId: Long, request: AdminCancelOrderRequest): AdminOrderResponse {
        val order = orderRepository.findByIdWithCustomer(orderId)?.order ?: throw orderNotFound()
        if (order.status == OrderStatus.PENDING && orderCancellation.settleOpenPayment(orderId)) {
            throw ConflictException("This order was just paid; check it and cancel again if needed")
        }
        orderCancellation.cancelByAdmin(adminId, orderId, request.reason!!.trim())
        return get(orderId)
    }

    // Shipped and delivered tell the customer; PROCESSING has no email (no notification type for it).
    private fun notifyCustomer(order: Order, to: OrderStatus, note: String?) {
        if (to != OrderStatus.SHIPPED && to != OrderStatus.DELIVERED) return
        val customer = customerRepository.findById(order.customerId)
            ?: throw IllegalStateException("Customer ${order.customerId} of order ${order.reference} does not exist")
        if (to == OrderStatus.SHIPPED) {
            notificationService.queueToCustomer(
                NotificationType.ORDER_SHIPPED, customer,
                NotificationMessages.orderShipped(customer.firstName, order.reference, note), orderId = order.orderId,
            )
        } else {
            notificationService.queueToCustomer(
                NotificationType.ORDER_DELIVERED, customer,
                NotificationMessages.orderDelivered(customer.firstName, order.reference), orderId = order.orderId,
            )
        }
    }

    private fun toResponse(row: OrderWithCustomer): AdminOrderResponse =
        AdminOrderResponse.from(
            row,
            orderItemRepository.findByOrderId(row.order.orderId),
            statusHistoryRepository.findByOrderId(row.order.orderId),
            paymentRepository.findByOrder(row.order.orderId),
            OrderStatusRules.nextStatuses(row.order.status, OrderStatusActor.ADMIN).filter { it in FULFILMENT_STATUSES },
            deliveryMethodRepository.findById(row.order.deliveryMethodId)!!,
            canCancel = OrderStatus.CANCELLED in OrderStatusRules.nextStatuses(row.order.status, OrderStatusActor.ADMIN),
        )

    private fun parseStatus(status: String?): OrderStatus? {
        val value = status?.trim()?.uppercase()?.ifEmpty { null } ?: return null
        return OrderStatus.entries.firstOrNull { it.name == value }
            ?: throw BadRequestException("status must be one of ${OrderStatus.entries.joinToString()}")
    }

    private fun orderNotFound() = NotFoundException("Order not found")

    companion object {
        // What an admin may set with PATCH .../status (UpdateOrderStatusRequest). Cancel has its own endpoint.
        val FULFILMENT_STATUSES = setOf(OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.DELIVERED)
    }
}
