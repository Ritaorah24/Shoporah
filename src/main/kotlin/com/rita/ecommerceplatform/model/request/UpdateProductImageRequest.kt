package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

/**
 * Body of PATCH /{stage}/admin/products/{productId}/images/{imageId}. Every field is optional; at least one is required.
 * altText "" clears it. primary can only be true: a product with images always has one primary, so the way to
 * move it is to make another image primary.
 */
data class UpdateProductImageRequest(
    @field:Size(max = 200)
    val altText: String? = null,
    @field:Min(0)
    @field:Max(999)
    val sortOrder: Int? = null,
    val primary: Boolean? = null,
)
