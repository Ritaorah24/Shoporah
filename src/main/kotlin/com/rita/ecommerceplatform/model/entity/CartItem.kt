package com.rita.ecommerceplatform.model.entity

import java.math.BigDecimal

/**
 * One cart line joined with its product and category. ec_cart_item stores only the product and the
 * quantity; the prices, stock and statuses here are read live from ec_product and ec_category, so
 * the cart always reflects the current price and whether the product can still be bought.
 */
data class CartItem(
    var productId: Long? = null,
    var cartItemQuantity: Int? = null,
    var productName: String? = null,
    var productSku: String? = null,
    var productPrice: BigDecimal? = null,
    var productDiscountPrice: BigDecimal? = null,
    var productSellingPrice: BigDecimal? = null,
    var productStockQuantity: Int? = null,
    var productStatus: ProductStatus? = null,
    var categoryStatus: CategoryStatus? = null,
)
