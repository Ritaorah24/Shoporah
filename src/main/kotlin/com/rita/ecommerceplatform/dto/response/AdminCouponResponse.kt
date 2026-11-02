package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.Coupon
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
            couponId = coupon.couponId,
            code = coupon.code,
            description = coupon.description,
            discountType = coupon.discountType.name,
            discountValue = coupon.discountValue,
            maxDiscountAmount = coupon.maxDiscountAmount,
            minimumOrderValue = coupon.minimumOrderValue,
            usageLimit = coupon.usageLimit,
            usageCount = coupon.usageCount,
            startsAt = coupon.startsAt,
            expiresAt = coupon.expiresAt,
            status = coupon.status.name,
            state = coupon.state(now).name,
            createdByAdminId = coupon.createdByAdminId,
            createdAt = coupon.createdAt,
            updatedAt = coupon.updatedAt,
        )
    }
}
