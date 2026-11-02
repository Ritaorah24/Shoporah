package com.rita.ecommerceplatform.model

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * One wishlist entry joined with its product and category. ec_wishlist_item stores only the product, so the
 * prices, stock and statuses here are read live, like a cart line (CartItem).
 */
data class WishlistItem(
    val productId: Long,
    val productName: String,
    val price: BigDecimal,
    val discountPrice: BigDecimal?,
    val sellingPrice: BigDecimal,
    val stockQuantity: Int,
    val lowStockThreshold: Int?,
    val productStatus: ProductStatus,
    val categoryStatus: CategoryStatus,
    val addedAt: LocalDateTime,
) {
    /** Whether the public can still see the product (the same rule as the product pages and the cart). */
    val available: Boolean
        get() = productStatus == ProductStatus.ACTIVE && categoryStatus == CategoryStatus.ACTIVE

    /** The same rule as Product.availability(); null when the product is no longer available at all. */
    fun availability(): Availability? = when {
        !available -> null
        stockQuantity <= 0 -> Availability.OUT_OF_STOCK
        lowStockThreshold != null && stockQuantity <= lowStockThreshold -> Availability.LOW_STOCK
        else -> Availability.IN_STOCK
    }
}
