package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/** One row of public.ec_customer. */
data class Customer(
    var customerId: Long? = null,
    var customerFirstName: String? = null,
    var customerLastName: String? = null,
    var customerEmail: String? = null,
    var customerPhoneNumber: String? = null,
    var customerStatus: CustomerStatus? = null,
    var customerCreatedAt: LocalDateTime? = null,
    var customerUpdatedAt: LocalDateTime? = null,
)

/** Allowed values of ec_customer.customerstatus (ck__ec_customer__status). */
enum class CustomerStatus { ACTIVE, SUSPENDED, DEACTIVATED, DELETED }
