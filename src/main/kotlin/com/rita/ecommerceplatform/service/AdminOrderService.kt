package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.dto.request.UpdateOrderStatusRequest
import com.rita.ecommerceplatform.dto.response.AdminOrderResponse
import com.rita.ecommerceplatform.dto.response.AdminOrderSummaryResponse
import com.rita.ecommerceplatform.dto.response.PageResponse
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.OrderStatus
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
        statusHistoryRepository.insert(
            orderId = orderId,
            fromStatus = order.status,
            toStatus = to,
            adminId = adminId,
            customerId = null,
            note = request.note?.trim()?.ifEmpty { null },
        )
        return get(orderId)
    }

    private fun toResponse(row: OrderWithCustomer): AdminOrderResponse =
        AdminOrderResponse.from(
            row,
            orderItemRepository.findByOrderId(row.order.orderId),
            statusHistoryRepository.findByOrderId(row.order.orderId),
            paymentRepository.findByOrder(row.order.orderId),
            OrderStatusRules.nextStatuses(row.order.status, OrderStatusActor.ADMIN).filter { it in FULFILMENT_STATUSES },
        )

    private fun parseStatus(status: String?): OrderStatus? {
        val value = status?.trim()?.uppercase()?.ifEmpty { null } ?: return null
        return OrderStatus.entries.firstOrNull { it.name == value }
            ?: throw BadRequestException("status must be one of ${OrderStatus.entries.joinToString()}")
    }

    private fun orderNotFound() = NotFoundException("Order not found")

    companion object {
        // What an admin may set in V1 (UpdateOrderStatusRequest). Cancel and refund come with V2's stock restore.
        val FULFILMENT_STATUSES = setOf(OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.DELIVERED)
    }
}
