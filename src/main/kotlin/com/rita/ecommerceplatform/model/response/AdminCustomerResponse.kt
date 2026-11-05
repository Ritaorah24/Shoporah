package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Customer
import com.rita.ecommerceplatform.repository.database.CustomerOrderTotals
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * A customer as admins see them, with their order totals. totalSpent is the sum of their PAID orders
 * (refunded orders no longer count). Their orders are listed by GET /api/admin/orders?customerId=...
 * Nothing from the auth table (password hash, reset token) is ever included.
 */
data class AdminCustomerResponse(
    val customerId: Long,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phoneNumber: String?,
    val status: String,
    val orderCount: Long,
    val paidOrderCount: Long,
    val totalSpent: BigDecimal,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(customer: Customer, totals: CustomerOrderTotals) = AdminCustomerResponse(
            customerId = customer.customerId,
            firstName = customer.firstName,
            lastName = customer.lastName,
            email = customer.email,
            phoneNumber = customer.phoneNumber,
            status = customer.status.name,
            orderCount = totals.orderCount,
            paidOrderCount = totals.paidOrderCount,
            totalSpent = totals.totalSpent,
            createdAt = customer.createdAt,
            updatedAt = customer.updatedAt,
        )
    }
}
