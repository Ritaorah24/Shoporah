package com.rita.ecommerceplatform.model.request

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/** Body of POST /{stage}/customer/addresses. Every text field is trimmed before validation. */
data class CreateAddressRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 150)
    val recipientName: String?,

    // Same rule as the phone number at registration.
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optionally starting with +")
    val recipientPhoneNumber: String?,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 255)
    val addressLine1: String?,

    // Optional; blank is stored as none.
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 255)
    val addressLine2: String? = null,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 100)
    val city: String?,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 100)
    val state: String?,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 100)
    val country: String?,

    // Optional; blank is stored as none.
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 20)
    val postalCode: String? = null,

    // true makes this the default address. The customer's first address is always the default.
    @JsonProperty("isDefault")
    val isDefault: Boolean? = null,
)
