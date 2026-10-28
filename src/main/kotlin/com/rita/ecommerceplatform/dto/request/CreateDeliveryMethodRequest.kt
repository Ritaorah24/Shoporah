package com.rita.ecommerceplatform.dto.request

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize
import java.math.BigDecimal

/**
 * Body of POST /api/admin/delivery-methods. The fee limit matches the NUMERIC(12, 2) column.
 * "estimatedMaxDays must not be less than estimatedMinDays" involves two fields, so DeliveryMethodService checks it.
 */
data class CreateDeliveryMethodRequest(
    // Stored in uppercase; what shoppers send at checkout, so it cannot be changed later.
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Za-z0-9_-]{2,30}$", message = "must be 2-30 letters, digits, - or _")
    val code: String?,

    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 100)
    val name: String?,

    // Optional; blank is stored as no description.
    @field:Size(max = 1000)
    val description: String? = null,

    @field:NotNull
    @field:DecimalMin(value = "0.00", message = "must not be negative")
    @field:Digits(integer = 10, fraction = 2)
    val fee: BigDecimal?,

    @field:Min(0)
    @field:Max(365)
    val estimatedMinDays: Int? = null,

    @field:Min(0)
    @field:Max(365)
    val estimatedMaxDays: Int? = null,
)
