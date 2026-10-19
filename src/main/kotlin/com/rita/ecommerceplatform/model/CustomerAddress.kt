package com.rita.ecommerceplatform.model

import java.time.LocalDateTime

/**
 * The delivery details of an address. Kept separate from CustomerAddress because an order
 * stores its own copy of them (ec_order.orderrecipientname ... orderpostalcode).
 */
data class AddressDetails(
    val recipientName: String,
    val recipientPhoneNumber: String,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val state: String,
    val country: String,
    val postalCode: String?,
)

/** One row of public.ec_customer_address. Addresses are hard-deleted; orders keep a snapshot. */
data class CustomerAddress(
    val addressId: Long,
    val customerId: Long,
    val details: AddressDetails,
    val isDefault: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
