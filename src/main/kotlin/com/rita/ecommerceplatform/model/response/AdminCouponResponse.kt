package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Coupon
import java.math.BigDecimal
import java.time.LocalDateTime

/** Coupon as shown to admins. There is no public coupon response: customers only learn a code by typing it. */
data class AdminCouponResponse(
    val couponId: Int,
    val code: String,
    val description: String?,
    val discountType: String,
    val discountValue: BigDecimal,
    val maxDiscountAmount: BigDecimal?,
    val minimumOrderValue: BigDecimal,
    val usageLimit: Int?,
    val usageCount: Int,
    val startsAt: LocalDateTime?,
    val expiresAt: LocalDateTime?,
    // ACTIVE or INACTIVE: what the admin set.
    val status: String,
    // USABLE, INACTIVE, SCHEDULED, EXPIRED or EXHAUSTED: whether the coupon works right now, and if not, why.
    val state: String,
    val createdByAdminId: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(coupon: Coupon, now: LocalDateTime) = AdminCouponResponse(
            couponId = coupon.couponId!!,
            code = coupon.couponCode!!,
            description = coupon.couponDescription,
            discountType = coupon.couponDiscountType!!.name,
            discountValue = coupon.couponDiscountValue!!,
            maxDiscountAmount = coupon.couponMaxDiscountAmount,
            minimumOrderValue = coupon.couponMinimumOrderValue!!,
            usageLimit = coupon.couponUsageLimit,
            usageCount = coupon.couponUsageCount!!,
            startsAt = coupon.couponStartsAt,
            expiresAt = coupon.couponExpiresAt,
            status = coupon.couponStatus!!.name,
            state = coupon.state(now).name,
            createdByAdminId = coupon.couponCreatedByAdminId!!,
            createdAt = coupon.couponCreatedAt!!,
            updatedAt = coupon.couponUpdatedAt!!,
        )
    }
}
