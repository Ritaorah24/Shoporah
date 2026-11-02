package com.rita.ecommerceplatform.dto.request

import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Body of POST /api/customer/orders (optional). There are deliberately no price or total fields:
 * the order is priced from the database, and any such field a client sends is ignored.
 */
data class CheckoutRequest(
    // One of the customer's own addresses; when left out, the default address is used.
    val addressId: Long? = null,

    // An active delivery method's code (GET /api/delivery-methods); when left out, STANDARD is used.
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 30)
    val deliveryMethodCode: String? = null,

    // Optional coupon code, any case (POST /api/customer/coupons/validate previews it). The discount comes off the subtotal.
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 30)
    val couponCode: String? = null,
)
