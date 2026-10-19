package com.rita.ecommerceplatform.model

import java.math.BigDecimal

/**
 * One cart line joined with its product and category. ec_cart_item stores only the product and the
 * quantity; the prices, stock and statuses here are read live from ec_product and ec_category, so
 * the cart always reflects the current price and whether the product can still be bought.
 */
data class CartItem(
    val productId: Long,
    val quantity: Int,
    val productName: String,
    val productSku: String,
    val price: BigDecimal,
    val discountPrice: BigDecimal?,
    val sellingPrice: BigDecimal,
    val stockQuantity: Int,
    val productStatus: ProductStatus,
    val categoryStatus: CategoryStatus,
)
