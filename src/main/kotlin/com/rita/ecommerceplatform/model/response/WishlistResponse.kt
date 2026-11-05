package com.rita.ecommerceplatform.model.response

import java.math.BigDecimal
import java.time.LocalDateTime

/** The customer's wishlist, newest first. Prices are the product's current ones; a wishlist stores none. */
data class WishlistResponse(
    val items: List<WishlistItemResponse>,
    val itemCount: Int,
)

data class WishlistItemResponse(
    val productId: Long,
    val name: String,
    // The product's primary image; null when it has none.
    val imageUrl: String?,
    val price: BigDecimal,
    val discountPrice: BigDecimal?,
    val sellingPrice: BigDecimal,
    // IN_STOCK, LOW_STOCK or OUT_OF_STOCK, as on the product page; null when the product is no longer available.
    val availability: String?,
    // False once the product is deactivated, deleted or in a hidden category: it can be removed but not moved to the cart.
    val available: Boolean,
    val addedAt: LocalDateTime,
)
