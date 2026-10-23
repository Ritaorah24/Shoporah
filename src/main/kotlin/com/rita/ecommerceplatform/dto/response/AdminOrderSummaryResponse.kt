package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.repository.OrderWithCustomer
import java.math.BigDecimal
import java.time.LocalDateTime

/** One order in the admin order list, with who placed it. GET /api/admin/orders/{orderId} has the full order. */
data class AdminOrderSummaryResponse(
    val orderId: Long,
    val reference: String,
    val customerId: Long,
    val customerName: String,
    val customerEmail: String,
    val status: String,
    val paymentStatus: String,
    val total: BigDecimal,
    // Total quantity of all items in the order.
    val itemCount: Int,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(row: OrderWithCustomer, itemCount: Int) = AdminOrderSummaryResponse(
            orderId = row.order.orderId,
            reference = row.order.reference,
            customerId = row.order.customerId,
            customerName = row.customerName,
            customerEmail = row.customerEmail,
            status = row.order.status.name,
            paymentStatus = row.order.paymentStatus.name,
            total = row.order.total,
            itemCount = itemCount,
            createdAt = row.order.createdAt,
        )
    }
}
