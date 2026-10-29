package com.rita.ecommerceplatform.dto.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Body of POST /api/admin/products/{productId}/stock-adjustments. `change` is how much to add (negative to remove),
 * not the new total, so a sale made meanwhile is never overwritten. 0 is refused by the service.
 * The reason is required: it is saved as the note of the inventory movement.
 */
data class StockAdjustmentRequest(
    @field:NotNull
    @field:Min(-100_000)
    @field:Max(100_000)
    val change: Int?,
    @field:NotBlank
    @field:Size(max = 300)
    val reason: String?,
)
