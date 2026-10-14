package com.rita.ecommerceplatform.model

import java.time.LocalDateTime

/** One row of public.ec_customer. */
data class Customer(
    val customerId: Long,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phoneNumber: String?,
    val status: CustomerStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Allowed values of ec_customer.customerstatus (ck__ec_customer__status). */
enum class CustomerStatus { ACTIVE, SUSPENDED, DEACTIVATED, DELETED }
