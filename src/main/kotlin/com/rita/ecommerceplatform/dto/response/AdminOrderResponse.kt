package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.DeliveryMethod
import com.rita.ecommerceplatform.model.OrderItem
import com.rita.ecommerceplatform.model.OrderStatus
import com.rita.ecommerceplatform.model.OrderStatusChange
import com.rita.ecommerceplatform.model.Payment
import com.rita.ecommerceplatform.repository.OrderWithCustomer
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * An order as an admin sees it: what the customer sees, plus who placed it, who made each status change,
 * every payment attempt (e.g. to find one noted "refund needed"), and the statuses it can move to next.
 */
data class AdminOrderResponse(
    val orderId: Long,
    val reference: String,
    val customerId: Long,
    val customerName: String,
    val customerEmail: String,
    val status: String,
    val paymentStatus: String,
    val paidAt: LocalDateTime?,
    // What PATCH .../status accepts now; empty when the admin has nothing to do (unpaid, delivered, cancelled).
    val nextStatuses: List<String>,
    // Whether POST .../cancel is allowed now: before shipping (PENDING, CONFIRMED, PROCESSING).
    val canCancel: Boolean,
    val items: List<OrderItemResponse>,
    val subtotal: BigDecimal,
    val deliveryMethod: OrderDeliveryMethodResponse,
    // The fee as it was at checkout, even if the method's fee has changed since.
    val deliveryFee: BigDecimal,
    val discountAmount: BigDecimal,
    val total: BigDecimal,
    val deliveryAddress: DeliveryAddressResponse,
    val statusHistory: List<AdminStatusChangeResponse>,
    val payments: List<AdminPaymentResponse>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(
            row: OrderWithCustomer,
            items: List<OrderItem>,
            history: List<OrderStatusChange>,
            payments: List<Payment>,
            nextStatuses: List<OrderStatus>,
            deliveryMethod: DeliveryMethod,
            canCancel: Boolean,
        ) = AdminOrderResponse(
            orderId = row.order.orderId,
            reference = row.order.reference,
            customerId = row.order.customerId,
            customerName = row.customerName,
            customerEmail = row.customerEmail,
            status = row.order.status.name,
            paymentStatus = row.order.paymentStatus.name,
            paidAt = row.order.paidAt,
            nextStatuses = nextStatuses.map { it.name },
            canCancel = canCancel,
            items = items.map(OrderItemResponse::from),
            subtotal = row.order.subtotal,
            deliveryMethod = OrderDeliveryMethodResponse.from(deliveryMethod),
            deliveryFee = row.order.deliveryFee,
            discountAmount = row.order.discountAmount,
            total = row.order.total,
            deliveryAddress = DeliveryAddressResponse.from(row.order.deliveryAddress),
            statusHistory = history.map(AdminStatusChangeResponse::from),
            payments = payments.map(AdminPaymentResponse::from),
            createdAt = row.order.createdAt,
            updatedAt = row.order.updatedAt,
        )
    }
}

/** One step in the order's timeline, with who made it: ADMIN (with adminId), CUSTOMER or SYSTEM. */
data class AdminStatusChangeResponse(
    val fromStatus: String?,
    val status: String,
    val changedBy: String,
    val adminId: Int?,
    val changedAt: LocalDateTime,
    val note: String?,
) {
    companion object {
        fun from(change: OrderStatusChange) = AdminStatusChangeResponse(
            fromStatus = change.fromStatus?.name,
            status = change.toStatus.name,
            changedBy = when {
                change.changedByAdminId != null -> "ADMIN"
                change.changedByCustomerId != null -> "CUSTOMER"
                else -> "SYSTEM"
            },
            adminId = change.changedByAdminId,
            changedAt = change.createdAt,
            note = change.note,
        )
    }
}

/** One payment attempt. gatewayResponse is Paystack's message, or our note (e.g. "..., refund needed"). */
data class AdminPaymentResponse(
    val reference: String,
    val provider: String,
    val status: String,
    val amount: BigDecimal,
    val currency: String,
    val channel: String?,
    val gatewayResponse: String?,
    val paidAt: LocalDateTime?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(payment: Payment) = AdminPaymentResponse(
            reference = payment.reference,
            provider = payment.provider,
            status = payment.status.name,
            amount = payment.amount,
            currency = payment.currency,
            channel = payment.channel,
            gatewayResponse = payment.gatewayResponse,
            paidAt = payment.paidAt,
            createdAt = payment.createdAt,
        )
    }
}
