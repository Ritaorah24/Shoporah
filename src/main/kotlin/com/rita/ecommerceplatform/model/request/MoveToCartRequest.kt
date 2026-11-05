package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/** Body of POST /api/customer/wishlist/items/{productId}/move-to-cart (optional). */
data class MoveToCartRequest(
    // Added to the quantity already in the cart. Defaults to 1 when not sent.
    @field:NotNull
    @field:Min(1)
    val quantity: Int? = 1,
)
