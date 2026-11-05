package com.rita.ecommerceplatform.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * One wishlist entry joined with its product and category. ec_wishlist_item stores only the product, so the
 * prices, stock and statuses here are read live, like a cart line (CartItem).
 */
data class WishlistItem(
    var productId: Long? = null,
    var productName: String? = null,
    var productPrice: BigDecimal? = null,
    var productDiscountPrice: BigDecimal? = null,
    var productSellingPrice: BigDecimal? = null,
    var productStockQuantity: Int? = null,
    var productLowStockThreshold: Int? = null,
    var productStatus: ProductStatus? = null,
    var categoryStatus: CategoryStatus? = null,
    var wishlistItemCreatedAt: LocalDateTime? = null,
) {
    /** Whether the public can still see the product (the same rule as the product pages and the cart). */
    val available: Boolean
        get() = productStatus == ProductStatus.ACTIVE && categoryStatus == CategoryStatus.ACTIVE

    /** The same rule as Product.availability(); null when the product is no longer available at all. */
    fun availability(): Availability? = when {
        !available -> null
        productStockQuantity!! <= 0 -> Availability.OUT_OF_STOCK
        productLowStockThreshold != null && productStockQuantity!! <= productLowStockThreshold!! -> Availability.LOW_STOCK
        else -> Availability.IN_STOCK
    }
}
