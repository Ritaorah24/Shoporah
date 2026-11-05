package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.DeliveryMethod
import com.rita.ecommerceplatform.model.entity.OrderItem
import com.rita.ecommerceplatform.model.entity.OrderStatus
import com.rita.ecommerceplatform.model.entity.OrderStatusChange
import com.rita.ecommerceplatform.model.entity.Payment
import com.rita.ecommerceplatform.model.entity.Refund
import com.rita.ecommerceplatform.repository.database.OrderWithCustomer
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
    // Whether POST .../refund is allowed now: a paid order that is CANCELLED or DELIVERED, with no refund in progress.
    val canRefund: Boolean,
    val items: List<OrderItemResponse>,
    val subtotal: BigDecimal,
    val deliveryMethod: OrderDeliveryMethodResponse,
    // The fee as it was at checkout, even if the method's fee has changed since.
    val deliveryFee: BigDecimal,
    // The coupon used at checkout (null without one); discountAmount is what it took off the subtotal.
    val couponCode: String?,
    val discountAmount: BigDecimal,
    val total: BigDecimal,
    val deliveryAddress: DeliveryAddressResponse,
    val statusHistory: List<AdminStatusChangeResponse>,
    val payments: List<AdminPaymentResponse>,
    val refunds: List<AdminRefundResponse>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(
            row: OrderWithCustomer,
            items: List<OrderItem>,
            history: List<OrderStatusChange>,
            payments: List<Payment>,
            refunds: List<Refund>,
            nextStatuses: List<OrderStatus>,
            deliveryMethod: DeliveryMethod,
            couponCode: String?,
            canCancel: Boolean,
            canRefund: Boolean,
        ) = AdminOrderResponse(
            orderId = row.order.orderId!!,
            reference = row.order.orderReference!!,
            customerId = row.order.orderCustomerId!!,
            customerName = row.customerName,
            customerEmail = row.customerEmail,
            status = row.order.orderStatus!!.name,
            paymentStatus = row.order.orderPaymentStatus!!.name,
            paidAt = row.order.orderPaidAt,
            nextStatuses = nextStatuses.map { it.name },
            canCancel = canCancel,
            canRefund = canRefund,
            items = items.map(OrderItemResponse::from),
            subtotal = row.order.orderSubtotal!!,
            deliveryMethod = OrderDeliveryMethodResponse.from(deliveryMethod),
            deliveryFee = row.order.orderDeliveryFee!!,
            couponCode = couponCode,
            discountAmount = row.order.orderDiscountAmount!!,
            total = row.order.orderTotal!!,
            deliveryAddress = DeliveryAddressResponse.from(row.order.deliveryAddress),
            statusHistory = history.map(AdminStatusChangeResponse::from),
            payments = payments.map(AdminPaymentResponse::from),
            refunds = refunds.map(AdminRefundResponse::from),
            createdAt = row.order.orderCreatedAt!!,
            updatedAt = row.order.orderUpdatedAt!!,
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
            fromStatus = change.orderStatusHistoryFromStatus?.name,
            status = change.orderStatusHistoryToStatus!!.name,
            changedBy = when {
                change.orderStatusHistoryChangedByAdminId != null -> "ADMIN"
                change.orderStatusHistoryChangedByCustomerId != null -> "CUSTOMER"
                else -> "SYSTEM"
            },
            adminId = change.orderStatusHistoryChangedByAdminId,
            changedAt = change.orderStatusHistoryCreatedAt!!,
            note = change.orderStatusHistoryNote,
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
            reference = payment.paymentReference!!,
            provider = payment.paymentProvider!!,
            status = payment.paymentStatus!!.name,
            amount = payment.paymentAmount!!,
            currency = payment.paymentCurrency!!,
            channel = payment.paymentChannel,
            gatewayResponse = payment.paymentGatewayResponse,
            paidAt = payment.paymentPaidAt,
            createdAt = payment.paymentCreatedAt!!,
        )
    }
}
