package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.UnableToLocateRecordException
import com.rita.ecommerceplatform.model.request.AdminCancelOrderRequest
import com.rita.ecommerceplatform.model.request.UpdateOrderStatusRequest
import com.rita.ecommerceplatform.model.response.AdminOrderResponse
import com.rita.ecommerceplatform.model.response.AdminOrderSummaryResponse
import com.rita.ecommerceplatform.model.response.PageResponse
import com.rita.ecommerceplatform.model.entity.NotificationType
import com.rita.ecommerceplatform.model.entity.Order
import com.rita.ecommerceplatform.model.entity.OrderPaymentStatus
import com.rita.ecommerceplatform.model.entity.OrderStatus
import com.rita.ecommerceplatform.model.entity.RefundStatus
import com.rita.ecommerceplatform.repository.database.AdminOrderSearch
import com.rita.ecommerceplatform.repository.database.CouponRepository
import com.rita.ecommerceplatform.repository.database.CustomerRepository
import com.rita.ecommerceplatform.repository.database.DeliveryMethodRepository
import com.rita.ecommerceplatform.repository.database.OrderItemRepository
import com.rita.ecommerceplatform.repository.database.OrderRepository
import com.rita.ecommerceplatform.repository.database.OrderStatusHistoryRepository
import com.rita.ecommerceplatform.repository.database.OrderWithCustomer
import com.rita.ecommerceplatform.repository.database.PaymentRepository
import com.rita.ecommerceplatform.repository.database.RefundRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** The admin order list's query parameters, as sent (checked in AdminOrderService). from / to are days, both inclusive. */
data class AdminOrderFilters(
    val status: String? = null,
    val paymentStatus: String? = null,
    // The start of a reference, e.g. ORD-20261029 for that day's orders, or the whole reference.
    val reference: String? = null,
    val customerId: Long? = null,
    // Part of the customer's email or name.
    val customer: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

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
    private val refundRepository: RefundRepository,
    private val couponRepository: CouponRepository,
) {

    /**
     * Every customer's orders, newest first, narrowed by any of the filters (all must match).
     * Paging is capped like the other lists (D6).
     */
    fun list(page: Int, size: Int, filters: AdminOrderFilters = AdminOrderFilters()): PageResponse<AdminOrderSummaryResponse> {
        val search = toSearch(filters)
        val safePage = Paging.page(page)
        val safeSize = Paging.size(size)
        val rows = orderRepository.findAllWithCustomer(search, safeSize, Paging.offset(safePage, safeSize))
        // One grouped query for the whole page, not one per order.
        val quantities = orderItemRepository.quantitiesByOrderIds(rows.map { it.order.orderId!! })
        return PageResponse.of(
            rows.map { AdminOrderSummaryResponse.from(it, quantities[it.order.orderId] ?: 0) },
            safePage,
            safeSize,
            orderRepository.countAll(search),
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
        OrderStatusRules.ensureCanChange(order.orderStatus!!, to, OrderStatusActor.ADMIN)

        // The lock makes this always 1; the WHERE on the old status is a second guard.
        check(orderRepository.updateStatus(orderId, order.orderStatus!!, to) == 1) { "Order $orderId changed while locked" }
        val note = request.note?.trim()?.ifEmpty { null }
        statusHistoryRepository.insert(
            orderId = orderId,
            fromStatus = order.orderStatus,
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
        if (order.orderStatus == OrderStatus.PENDING && orderCancellation.settleOpenPayment(orderId)) {
            throw BadRequestException("This order was just paid; check it and cancel again if needed")
        }
        orderCancellation.cancelByAdmin(adminId, orderId, request.reason!!.trim())
        return get(orderId)
    }

    // Shipped and delivered tell the customer; PROCESSING has no email (no notification type for it).
    private fun notifyCustomer(order: Order, to: OrderStatus, note: String?) {
        if (to != OrderStatus.SHIPPED && to != OrderStatus.DELIVERED) return
        val customer = customerRepository.findById(order.orderCustomerId!!)
            ?: throw IllegalStateException("Customer ${order.orderCustomerId} of order ${order.orderReference} does not exist")
        if (to == OrderStatus.SHIPPED) {
            notificationService.queueToCustomer(
                NotificationType.ORDER_SHIPPED, customer,
                NotificationMessages.orderShipped(customer.customerFirstName!!, order.orderReference!!, note), orderId = order.orderId,
            )
        } else {
            notificationService.queueToCustomer(
                NotificationType.ORDER_DELIVERED, customer,
                NotificationMessages.orderDelivered(customer.customerFirstName!!, order.orderReference!!), orderId = order.orderId,
            )
        }
    }

    private fun toResponse(row: OrderWithCustomer): AdminOrderResponse {
        val refunds = refundRepository.findByOrder(row.order.orderId!!)
        return AdminOrderResponse.from(
            row,
            orderItemRepository.findByOrderId(row.order.orderId!!),
            statusHistoryRepository.findByOrderId(row.order.orderId!!),
            paymentRepository.findByOrder(row.order.orderId!!),
            refunds,
            OrderStatusRules.nextStatuses(row.order.orderStatus!!, OrderStatusActor.ADMIN).filter { it in FULFILMENT_STATUSES },
            deliveryMethodRepository.findById(row.order.orderDeliveryMethodId!!)!!,
            row.order.orderCouponId?.let { couponRepository.findById(it)!!.couponCode },
            canCancel = OrderStatus.CANCELLED in OrderStatusRules.nextStatuses(row.order.orderStatus!!, OrderStatusActor.ADMIN),
            canRefund = RefundService.isRefundable(row.order) && refunds.all { it.refundStatus == RefundStatus.FAILED },
        )
    }

    // Blank text filters are ignored; a bad status or an inverted date range is 400.
    private fun toSearch(filters: AdminOrderFilters): AdminOrderSearch {
        if (filters.from != null && filters.to != null && filters.from > filters.to) {
            throw BadRequestException("from must not be after to")
        }
        return AdminOrderSearch(
            status = parseEnum(filters.status, "status", OrderStatus.entries),
            paymentStatus = parseEnum(filters.paymentStatus, "paymentStatus", OrderPaymentStatus.entries),
            referenceStartsWith = filters.reference?.trim()?.ifEmpty { null },
            customerId = filters.customerId,
            customerContains = filters.customer?.trim()?.ifEmpty { null },
            placedFrom = filters.from?.atStartOfDay(),
            // `to` is a whole day: everything before the next midnight.
            placedBefore = filters.to?.plusDays(1)?.atStartOfDay(),
        )
    }

    private fun <E : Enum<E>> parseEnum(value: String?, name: String, entries: List<E>): E? {
        val wanted = value?.trim()?.uppercase()?.ifEmpty { null } ?: return null
        return entries.firstOrNull { it.name == wanted }
            ?: throw BadRequestException("$name must be one of ${entries.joinToString()}")
    }

    private fun orderNotFound() = UnableToLocateRecordException("Order not found")

    companion object {
        // What an admin may set with PATCH .../status (UpdateOrderStatusRequest). Cancel has its own endpoint.
        val FULFILMENT_STATUSES = setOf(OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.DELIVERED)
    }
}
