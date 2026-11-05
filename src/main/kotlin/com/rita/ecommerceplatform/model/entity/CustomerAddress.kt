package com.rita.ecommerceplatform.model.entity

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
    var customerAddressId: Long? = null,
    var customerAddressCustomerId: Long? = null,
    var customerAddressRecipientName: String? = null,
    var customerAddressRecipientPhoneNumber: String? = null,
    var customerAddressLine1: String? = null,
    var customerAddressLine2: String? = null,
    var customerAddressCity: String? = null,
    var customerAddressState: String? = null,
    var customerAddressCountry: String? = null,
    var customerAddressPostalCode: String? = null,
    var customerAddressIsDefault: Boolean? = null,
    var customerAddressCreatedAt: LocalDateTime? = null,
    var customerAddressUpdatedAt: LocalDateTime? = null,
) {
    /** The delivery details as one value. */
    val details: AddressDetails
        get() = AddressDetails(
            recipientName = customerAddressRecipientName!!,
            recipientPhoneNumber = customerAddressRecipientPhoneNumber!!,
            addressLine1 = customerAddressLine1!!,
            addressLine2 = customerAddressLine2,
            city = customerAddressCity!!,
            state = customerAddressState!!,
            country = customerAddressCountry!!,
            postalCode = customerAddressPostalCode,
        )
}
