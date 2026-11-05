package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Customer
import java.time.LocalDateTime

/** Customer profile as returned to the client. Never contains credentials. */
data class CustomerProfileResponse(
    val customerId: Long,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phoneNumber: String?,
    val status: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(customer: Customer) = CustomerProfileResponse(
            customerId = customer.customerId,
            firstName = customer.firstName,
            lastName = customer.lastName,
            email = customer.email,
            phoneNumber = customer.phoneNumber,
            status = customer.status.name,
            createdAt = customer.createdAt,
        )
    }
}
