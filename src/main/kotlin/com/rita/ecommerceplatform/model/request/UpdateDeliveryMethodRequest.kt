package com.rita.ecommerceplatform.model.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize
import java.math.BigDecimal

/**
 * Body of PATCH /api/admin/delivery-methods/{id}. Every field is optional: a field that is not sent
 * (null) keeps its current value. The code never changes; status has its own endpoint.
 * A new fee applies to new orders only: orders keep the fee they were placed with.
 */
data class UpdateDeliveryMethodRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 100)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val name: String? = null,

    // "" (or only spaces) clears the description; leaving the field out keeps it.
    @field:Size(max = 1000)
    val description: String? = null,

    @field:DecimalMin(value = "0.00", message = "must not be negative")
    @field:Digits(integer = 10, fraction = 2)
    val fee: BigDecimal? = null,

    @field:Min(0)
    @field:Max(365)
    val estimatedMinDays: Int? = null,

    @field:Min(0)
    @field:Max(365)
    val estimatedMaxDays: Int? = null,
) {
    // Unknown fields (e.g. "code", "status") are collected so validation can reject them instead of ignoring them.
    @JsonIgnore
    private val unknownFields = mutableMapOf<String, Any?>()

    @JsonAnySetter
    fun addUnknownField(name: String, value: Any?) {
        unknownFields[name] = value
    }

    @get:JsonIgnore
    @get:AssertTrue(message = "unknown field (the code cannot be changed; use PATCH .../status to change the status)")
    val onlyKnownFields: Boolean
        get() = unknownFields.isEmpty()

    @get:JsonIgnore
    @get:AssertTrue(message = "send at least one of name, description, fee, estimatedMinDays, estimatedMaxDays")
    val hasSomethingToUpdate: Boolean
        get() = listOf(name, description, fee, estimatedMinDays, estimatedMaxDays).any { it != null }
}
