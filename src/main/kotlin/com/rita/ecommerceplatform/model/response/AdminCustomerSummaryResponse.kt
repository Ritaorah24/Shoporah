package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Customer
import java.time.LocalDateTime

/** One customer in the admin customer list. GET /api/admin/customers/{customerId} adds their order totals. */
data class AdminCustomerSummaryResponse(
    val customerId: Long,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phoneNumber: String?,
    val status: String,
    val orderCount: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(customer: Customer, orderCount: Long) = AdminCustomerSummaryResponse(
            customerId = customer.customerId,
            firstName = customer.firstName,
            lastName = customer.lastName,
            email = customer.email,
            phoneNumber = customer.phoneNumber,
            status = customer.status.name,
            orderCount = orderCount,
            createdAt = customer.createdAt,
        )
    }
}
