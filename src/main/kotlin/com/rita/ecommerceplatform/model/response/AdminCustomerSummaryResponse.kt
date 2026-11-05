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
            customerId = customer.customerId!!,
            firstName = customer.customerFirstName!!,
            lastName = customer.customerLastName!!,
            email = customer.customerEmail!!,
            phoneNumber = customer.customerPhoneNumber,
            status = customer.customerStatus!!.name,
            orderCount = orderCount,
            createdAt = customer.customerCreatedAt!!,
        )
    }
}
