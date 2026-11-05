package com.rita.ecommerceplatform.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * One row of public.ec_coupon. Money is in naira. usageLimit null = unlimited; startsAt / expiresAt null = no limit.
 * "Expired" and "exhausted" are not stored: state() works them out from the dates and the usage count.
 */
data class Coupon(
    var couponId: Int? = null,
    var couponCode: String? = null,
    var couponDescription: String? = null,
    var couponDiscountType: CouponDiscountType? = null,
    var couponDiscountValue: BigDecimal? = null,
    var couponMaxDiscountAmount: BigDecimal? = null,
    var couponMinimumOrderValue: BigDecimal? = null,
    var couponUsageLimit: Int? = null,
    var couponUsageCount: Int? = null,
    var couponStartsAt: LocalDateTime? = null,
    var couponExpiresAt: LocalDateTime? = null,
    var couponCreatedByAdminId: Int? = null,
    var couponStatus: CouponStatus? = null,
    var couponCreatedAt: LocalDateTime? = null,
    var couponUpdatedAt: LocalDateTime? = null,
) {
    /** Whether the coupon can be used at `now` (platform time), and if not, why. Valid from couponStartsAt until just before couponExpiresAt. */
    fun state(now: LocalDateTime): CouponState = when {
        couponStatus != CouponStatus.ACTIVE -> CouponState.INACTIVE
        couponExpiresAt != null && !now.isBefore(couponExpiresAt) -> CouponState.EXPIRED
        couponUsageLimit != null && couponUsageCount!! >= couponUsageLimit!! -> CouponState.EXHAUSTED
        couponStartsAt != null && now.isBefore(couponStartsAt) -> CouponState.SCHEDULED
        else -> CouponState.USABLE
    }
}

/** Allowed values of ec_coupon.coupondiscounttype (ck__ec_coupon__discounttype). */
enum class CouponDiscountType { PERCENTAGE, FIXED_AMOUNT }

/** Allowed values of ec_coupon.couponstatus (ck__ec_coupon__status). DELETED is final. */
enum class CouponStatus { ACTIVE, INACTIVE, DELETED }

/** What Coupon.state() returns; shown to admins so they can see at a glance whether a coupon works now. */
enum class CouponState { USABLE, INACTIVE, SCHEDULED, EXPIRED, EXHAUSTED }
