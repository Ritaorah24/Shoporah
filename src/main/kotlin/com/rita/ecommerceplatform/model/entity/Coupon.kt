package com.rita.ecommerceplatform.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * One row of public.ec_coupon. Money is in naira. usageLimit null = unlimited; startsAt / expiresAt null = no limit.
 * "Expired" and "exhausted" are not stored: state() works them out from the dates and the usage count.
 */
data class Coupon(
    val couponId: Int,
    val code: String,
    val description: String?,
    val discountType: CouponDiscountType,
    val discountValue: BigDecimal,
    val maxDiscountAmount: BigDecimal?,
    val minimumOrderValue: BigDecimal,
    val usageLimit: Int?,
    val usageCount: Int,
    val startsAt: LocalDateTime?,
    val expiresAt: LocalDateTime?,
    val createdByAdminId: Int,
    val status: CouponStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    /** Whether the coupon can be used at `now` (platform time), and if not, why. Valid from startsAt until just before expiresAt. */
    fun state(now: LocalDateTime): CouponState = when {
        status != CouponStatus.ACTIVE -> CouponState.INACTIVE
        expiresAt != null && !now.isBefore(expiresAt) -> CouponState.EXPIRED
        usageLimit != null && usageCount >= usageLimit -> CouponState.EXHAUSTED
        startsAt != null && now.isBefore(startsAt) -> CouponState.SCHEDULED
        else -> CouponState.USABLE
    }
}

/** Allowed values of ec_coupon.coupondiscounttype (ck__ec_coupon__discounttype). */
enum class CouponDiscountType { PERCENTAGE, FIXED_AMOUNT }

/** Allowed values of ec_coupon.couponstatus (ck__ec_coupon__status). DELETED is final. */
enum class CouponStatus { ACTIVE, INACTIVE, DELETED }

/** What Coupon.state() returns; shown to admins so they can see at a glance whether a coupon works now. */
enum class CouponState { USABLE, INACTIVE, SCHEDULED, EXPIRED, EXHAUSTED }
