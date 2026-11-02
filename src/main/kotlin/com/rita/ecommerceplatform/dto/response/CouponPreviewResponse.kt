package com.rita.ecommerceplatform.dto.response

import java.math.BigDecimal

/**
 * What a coupon would save on the customer's current cart. Nothing is reserved: checkout checks the coupon again.
 * The delivery fee is not included because the customer chooses the delivery method at checkout.
 */
data class CouponPreviewResponse(
    val code: String,
    val description: String?,
    val subtotal: BigDecimal,
    val discountAmount: BigDecimal,
    val subtotalAfterDiscount: BigDecimal,
)
