package com.rita.ecommerceplatform.model.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/** Body of PATCH /api/customer/cart/items/{productId}: the new quantity (replaces the current one). */
data class UpdateCartItemRequest(
    // To remove the item, use DELETE instead of 0.
    @field:NotNull
    @field:Min(1)
    val quantity: Int? = null,
) {
    // Unknown fields (e.g. "price") are collected so validation can reject them instead of ignoring them.
    @JsonIgnore
    private val unknownFields = mutableMapOf<String, Any?>()

    @JsonAnySetter
    fun addUnknownField(name: String, value: Any?) {
        unknownFields[name] = value
    }

    @get:JsonIgnore
    @get:AssertTrue(message = "only quantity can be updated")
    val onlyKnownFields: Boolean
        get() = unknownFields.isEmpty()
}
