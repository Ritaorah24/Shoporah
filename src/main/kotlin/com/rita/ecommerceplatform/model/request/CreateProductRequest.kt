package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize
import java.math.BigDecimal

/**
 * Body of POST /api/admin/products. Money limits match the NUMERIC(12, 2) columns.
 * "discountPrice must be less than price" involves two fields, so ProductService checks it.
 */
data class CreateProductRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 200)
    val name: String?,

    // Optional; blank is stored as no description.
    @field:Size(max = 5000)
    val description: String? = null,

    // Stored in uppercase; unique ignoring case, including deleted products.
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 64)
    @field:Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "may contain only letters, digits, - and _")
    val sku: String?,

    @field:NotNull
    val categoryId: Int?,

    @field:NotNull
    @field:DecimalMin(value = "0.01", message = "must be greater than 0")
    @field:Digits(integer = 10, fraction = 2)
    val price: BigDecimal?,

    @field:DecimalMin(value = "0.00", message = "must not be negative")
    @field:Digits(integer = 10, fraction = 2)
    val discountPrice: BigDecimal? = null,

    // Defaults to 0 when not sent.
    @field:Min(0)
    val stockQuantity: Int? = null,

    @field:Min(0)
    val lowStockThreshold: Int? = null,
)
