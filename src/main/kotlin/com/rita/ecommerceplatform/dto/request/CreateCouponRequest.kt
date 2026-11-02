package com.rita.ecommerceplatform.dto.request

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Body of POST /api/admin/coupons. Money limits match the NUMERIC(12, 2) columns; dates are platform time (UTC+1).
 * Rules that involve two fields (a PERCENTAGE value of at most 100, maxDiscountAmount only for PERCENTAGE,
 * expiresAt after startsAt and in the future) are checked by CouponService.
 */
data class CreateCouponRequest(
    // Stored in uppercase; what customers type at checkout, so it cannot be changed later.
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Za-z0-9_-]{3,30}$", message = "must be 3-30 letters, digits, - or _")
    val code: String?,

    // Optional; blank is stored as no description.
    @field:Size(max = 1000)
    val description: String? = null,

    // A String rather than an enum, so a wrong value gets a clear field error instead of "malformed JSON".
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Pattern(regexp = "(?i)PERCENTAGE|FIXED_AMOUNT", message = "must be PERCENTAGE or FIXED_AMOUNT")
    val discountType: String?,

    // A percentage (10 = 10% off) or an amount in naira, depending on discountType.
    @field:NotNull
    @field:DecimalMin(value = "0.01", message = "must be greater than 0")
    @field:Digits(integer = 10, fraction = 2)
    val discountValue: BigDecimal?,

    // PERCENTAGE only: the most a single order can save.
    @field:DecimalMin(value = "0.01", message = "must be greater than 0")
    @field:Digits(integer = 10, fraction = 2)
    val maxDiscountAmount: BigDecimal? = null,

    // Compared with the order subtotal; not sent = 0 (no minimum).
    @field:DecimalMin(value = "0.00", message = "must not be negative")
    @field:Digits(integer = 10, fraction = 2)
    val minimumOrderValue: BigDecimal? = null,

    // How many orders may use the coupon in total; not sent = unlimited.
    @field:Min(1)
    val usageLimit: Int? = null,

    // Not sent = usable from now on.
    val startsAt: LocalDateTime? = null,

    // Not sent = never expires.
    val expiresAt: LocalDateTime? = null,
)
