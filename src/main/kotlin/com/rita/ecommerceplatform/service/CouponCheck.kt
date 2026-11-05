package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.model.entity.Coupon
import com.rita.ecommerceplatform.model.entity.CouponDiscountType
import com.rita.ecommerceplatform.model.entity.CouponState
import com.rita.ecommerceplatform.model.entity.CouponStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The rules for using a coupon (checkpoint 3.1, Step 2), shared by the preview and checkout so they never disagree.
 * Every refusal is a code 106 with a clear reason, also sent as a couponCode field error.
 */
object CouponCheck {

    const val FIELD = "couponCode"

    private val START_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * The discount `coupon` gives on `subtotal` at `now` (platform time), or a code 106 saying why it cannot be used.
     * `typedCode` is what the customer sent, for the messages; null coupon = no such code.
     */
    fun discountFor(coupon: Coupon?, typedCode: String, subtotal: BigDecimal, now: LocalDateTime): BigDecimal {
        val code = coupon?.couponCode ?: typedCode.uppercase()
        if (coupon == null || coupon.couponStatus == CouponStatus.DELETED) refuse("Coupon $code does not exist")
        when (coupon.state(now)) {
            CouponState.INACTIVE -> refuse("Coupon $code is not active")
            CouponState.EXPIRED -> refuse("Coupon $code has expired")
            CouponState.EXHAUSTED -> refuse("Coupon $code has reached its usage limit")
            CouponState.SCHEDULED -> refuse("Coupon $code can be used from ${coupon.couponStartsAt!!.format(START_FORMAT)}")
            CouponState.USABLE -> Unit
        }
        if (subtotal < coupon.couponMinimumOrderValue) {
            refuse(
                "Coupon $code needs a subtotal of at least ${NotificationMessages.naira(coupon.couponMinimumOrderValue!!)} " +
                    "(yours is ${NotificationMessages.naira(subtotal)})"
            )
        }
        return amountOff(coupon, subtotal)
    }

    /** Percentage (rounded to the kobo, capped at maxDiscountAmount) or fixed; never more than the subtotal. */
    fun amountOff(coupon: Coupon, subtotal: BigDecimal): BigDecimal {
        val raw = when (coupon.couponDiscountType!!) {
            CouponDiscountType.PERCENTAGE -> {
                val percent = (subtotal * coupon.couponDiscountValue!!).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                coupon.couponMaxDiscountAmount?.let { percent.min(it) } ?: percent
            }
            CouponDiscountType.FIXED_AMOUNT -> coupon.couponDiscountValue
        }
        return raw!!.min(subtotal).setScale(2, RoundingMode.HALF_UP)
    }

    fun refuse(message: String): Nothing = throw BadRequestException(message, FIELD)

    private val HUNDRED = BigDecimal("100")
}
