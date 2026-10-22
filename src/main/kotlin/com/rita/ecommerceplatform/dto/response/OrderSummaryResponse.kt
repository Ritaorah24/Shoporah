package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.Order
import java.math.BigDecimal
import java.time.LocalDateTime

/** One order in the customer's order list (H1). GET /api/customer/orders/{orderId} has the full order. */
data class OrderSummaryResponse(
    val orderId: Long,
    val reference: String,
    val status: String,
    val paymentStatus: String,
    val total: BigDecimal,
    // Total quantity of all items in the order.
    val itemCount: Int,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(order: Order, itemCount: Int) = OrderSummaryResponse(
            orderId = order.orderId,
            reference = order.reference,
            status = order.status.name,
            paymentStatus = order.paymentStatus.name,
            total = order.total,
            itemCount = itemCount,
            createdAt = order.createdAt,
        )
    }
}
