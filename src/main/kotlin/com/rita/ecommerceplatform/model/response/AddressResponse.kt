package com.rita.ecommerceplatform.model.response

import com.fasterxml.jackson.annotation.JsonProperty
import com.rita.ecommerceplatform.model.entity.CustomerAddress

data class AddressResponse(
    val addressId: Long,
    val recipientName: String,
    val recipientPhoneNumber: String,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val state: String,
    val country: String,
    val postalCode: String?,
    // Without this, Jackson would name the field "default" (it drops the "is" of boolean getters).
    @get:JsonProperty("isDefault")
    val isDefault: Boolean,
) {
    companion object {
        fun from(address: CustomerAddress) = AddressResponse(
            addressId = address.addressId,
            recipientName = address.details.recipientName,
            recipientPhoneNumber = address.details.recipientPhoneNumber,
            addressLine1 = address.details.addressLine1,
            addressLine2 = address.details.addressLine2,
            city = address.details.city,
            state = address.details.state,
            country = address.details.country,
            postalCode = address.details.postalCode,
            isDefault = address.isDefault,
        )
    }
}
