package com.rita.ecommerceplatform.dto.request

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * Body of POST /api/customer/cart/items. There is deliberately no price field: prices always come
 * from the database, and any extra field a client sends (e.g. "price") is ignored.
 */
data class AddCartItemRequest(
    @field:NotNull
    val productId: Long?,

    // Added to the quantity already in the cart. Defaults to 1 when not sent.
    @field:NotNull
    @field:Min(1)
    val quantity: Int? = 1,
)
