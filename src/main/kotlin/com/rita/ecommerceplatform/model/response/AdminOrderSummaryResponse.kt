package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.repository.database.OrderWithCustomer
import java.math.BigDecimal
import java.time.LocalDateTime

/** One order in the admin order list, with who placed it. GET /{stage}/admin/orders/{orderId} has the full order. */
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
            orderId = row.order.orderId!!,
            reference = row.order.orderReference!!,
            customerId = row.order.orderCustomerId!!,
            customerName = row.customerName,
            customerEmail = row.customerEmail,
            status = row.order.orderStatus!!.name,
            paymentStatus = row.order.orderPaymentStatus!!.name,
            total = row.order.orderTotal!!,
            itemCount = itemCount,
            createdAt = row.order.orderCreatedAt!!,
        )
    }
}
