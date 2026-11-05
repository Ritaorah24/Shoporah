package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.NotNull

/** Body of POST /{stage}/customer/wishlist/items. A wishlist has no quantity: a product is on it or not. */
data class AddWishlistItemRequest(
    @field:NotNull
    val productId: Long?,
)
