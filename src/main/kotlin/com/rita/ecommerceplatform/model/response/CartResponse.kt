package com.rita.ecommerceplatform.model.response

import java.math.BigDecimal

/**
 * The customer's cart, priced from the database at the moment of the request. Items that cannot be
 * bought as they are (issue != null) are listed but left out of itemCount and subtotal (C3).
 */
data class CartResponse(
    val items: List<CartItemResponse>,
    // Total quantity of the items that can be bought.
    val itemCount: Int,
    val subtotal: BigDecimal,
    // False when the cart is empty or any item has an issue.
    val canCheckout: Boolean,
)

data class CartItemResponse(
    val productId: Long,
    val name: String,
    // The product's primary image (a thumbnail); null when it has none.
    val imageUrl: String?,
    val price: BigDecimal,
    val discountPrice: BigDecimal?,
    // What one unit costs: the discount price if there is one, otherwise the price.
    val unitPrice: BigDecimal,
    val quantity: Int,
    val lineTotal: BigDecimal,
    val available: Boolean,
    // PRODUCT_UNAVAILABLE, OUT_OF_STOCK or INSUFFICIENT_STOCK; null when the item can be bought.
    val issue: String?,
    // Set only for INSUFFICIENT_STOCK: how many can be bought now.
    val availableQuantity: Int?,
)
