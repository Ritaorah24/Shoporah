package com.rita.ecommerceplatform.dto.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Body of PATCH /api/customer/addresses/{id}. Every field is optional: a field that is not sent
 * (null) keeps its current value. Required fields cannot be blanked; addressLine2 and postalCode
 * are cleared with "". The default address is changed through its own endpoint, not here.
 */
data class UpdateAddressRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 150)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val recipientName: String? = null,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optionally starting with +")
    val recipientPhoneNumber: String? = null,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 255)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val addressLine1: String? = null,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 255)
    val addressLine2: String? = null,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 100)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val city: String? = null,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 100)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val state: String? = null,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 100)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val country: String? = null,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 20)
    val postalCode: String? = null,
) {
    // Unknown fields (e.g. "isDefault") are collected so validation can reject them instead of ignoring them.
    @JsonIgnore
    private val unknownFields = mutableMapOf<String, Any?>()

    @JsonAnySetter
    fun addUnknownField(name: String, value: Any?) {
        unknownFields[name] = value
    }

    @get:JsonIgnore
    @get:AssertTrue(message = "unknown field (use PATCH .../default to make this the default address)")
    val onlyKnownFields: Boolean
        get() = unknownFields.isEmpty()

    @get:JsonIgnore
    @get:AssertTrue(message = "send at least one field to update")
    val hasSomethingToUpdate: Boolean
        get() = listOf(recipientName, recipientPhoneNumber, addressLine1, addressLine2, city, state, country, postalCode)
            .any { it != null }
}
