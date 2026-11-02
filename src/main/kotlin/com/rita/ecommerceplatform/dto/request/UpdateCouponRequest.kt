package com.rita.ecommerceplatform.dto.request

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
import java.time.LocalDateTime

/**
 * Body of PATCH /api/admin/coupons/{id}. Every field is optional: a field that is not sent (null) keeps its
 * current value. The code never changes; status has its own endpoint. Orders already placed keep their discount.
 * The optional numbers and dates have no "empty" value, so each has a clear... flag to remove it.
 */
data class UpdateCouponRequest(
    // "" (or only spaces) clears the description; leaving the field out keeps it.
    @field:Size(max = 1000)
    val description: String? = null,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Pattern(regexp = "(?i)PERCENTAGE|FIXED_AMOUNT", message = "must be PERCENTAGE or FIXED_AMOUNT")
    val discountType: String? = null,

    @field:DecimalMin(value = "0.01", message = "must be greater than 0")
    @field:Digits(integer = 10, fraction = 2)
    val discountValue: BigDecimal? = null,

    @field:DecimalMin(value = "0.01", message = "must be greater than 0")
    @field:Digits(integer = 10, fraction = 2)
    val maxDiscountAmount: BigDecimal? = null,

    val clearMaxDiscountAmount: Boolean? = null,

    @field:DecimalMin(value = "0.00", message = "must not be negative")
    @field:Digits(integer = 10, fraction = 2)
    val minimumOrderValue: BigDecimal? = null,

    // Cannot go below the number of times the coupon has already been used (409).
    @field:Min(1)
    val usageLimit: Int? = null,

    val clearUsageLimit: Boolean? = null,

    val startsAt: LocalDateTime? = null,

    val clearStartsAt: Boolean? = null,

    val expiresAt: LocalDateTime? = null,

    val clearExpiresAt: Boolean? = null,
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
    @get:AssertTrue(message = "send at least one field to update")
    val hasSomethingToUpdate: Boolean
        get() = listOf(
            description, discountType, discountValue, maxDiscountAmount, clearMaxDiscountAmount, minimumOrderValue,
            usageLimit, clearUsageLimit, startsAt, clearStartsAt, expiresAt, clearExpiresAt,
        ).any { it != null }

    @get:JsonIgnore
    @get:AssertTrue(message = "send a value or its clear flag, not both")
    val notSetAndCleared: Boolean
        get() = !(maxDiscountAmount != null && clearMaxDiscountAmount == true) &&
            !(usageLimit != null && clearUsageLimit == true) &&
            !(startsAt != null && clearStartsAt == true) &&
            !(expiresAt != null && clearExpiresAt == true)
}
