package com.rita.ecommerceplatform.dto.request

/**
 * Body of POST /api/customer/orders (optional). There are deliberately no price or total fields:
 * the order is priced from the database, and any such field a client sends is ignored.
 */
data class CheckoutRequest(
    // One of the customer's own addresses; when left out, the default address is used.
    val addressId: Long? = null,
)
