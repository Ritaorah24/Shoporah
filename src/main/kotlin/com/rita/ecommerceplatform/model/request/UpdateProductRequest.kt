package com.rita.ecommerceplatform.model.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize
import java.math.BigDecimal

/**
 * Body of PATCH /{stage}/admin/products/{id}. Every field is optional: a field that is not sent
 * (null) keeps its current value. Status is changed through its own endpoint, not here.
 */
data class UpdateProductRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 200)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val name: String? = null,

    // "" (or only spaces) clears the description; leaving the field out keeps it.
    @field:Size(max = 5000)
    val description: String? = null,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(min = 1, max = 64)
    @field:Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "may contain only letters, digits, - and _")
    val sku: String? = null,

    val categoryId: Int? = null,

    @field:DecimalMin(value = "0.01", message = "must be greater than 0")
    @field:Digits(integer = 10, fraction = 2)
    val price: BigDecimal? = null,

    @field:DecimalMin(value = "0.00", message = "must not be negative")
    @field:Digits(integer = 10, fraction = 2)
    val discountPrice: BigDecimal? = null,

    // A number has no "empty" value (0 would mean free), so removing the discount needs its own flag.
    val clearDiscountPrice: Boolean? = null,

    @field:Min(0)
    val stockQuantity: Int? = null,

    @field:Min(0)
    val lowStockThreshold: Int? = null,
) {
    // Unknown fields (e.g. "status") are collected so validation can reject them instead of ignoring them.
    @JsonIgnore
    private val unknownFields = mutableMapOf<String, Any?>()

    @JsonAnySetter
    fun addUnknownField(name: String, value: Any?) {
        unknownFields[name] = value
    }

    @get:JsonIgnore
    @get:AssertTrue(message = "unknown field (use PATCH .../status to change the status)")
    val onlyKnownFields: Boolean
        get() = unknownFields.isEmpty()

    @get:JsonIgnore
    @get:AssertTrue(message = "send at least one field to update")
    val hasSomethingToUpdate: Boolean
        get() = listOf(
            name, description, sku, categoryId, price, discountPrice, clearDiscountPrice, stockQuantity, lowStockThreshold,
        ).any { it != null }

    @get:JsonIgnore
    @get:AssertTrue(message = "send either discountPrice or clearDiscountPrice, not both")
    val discountNotSetAndCleared: Boolean
        get() = !(discountPrice != null && clearDiscountPrice == true)
}
