package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.RecordAlreadyExistException
import com.rita.ecommerceplatform.exception.UnableToLocateRecordException
import com.rita.ecommerceplatform.model.request.CreateCouponRequest
import com.rita.ecommerceplatform.model.request.UpdateCouponRequest
import com.rita.ecommerceplatform.model.request.UpdateStatusRequest
import com.rita.ecommerceplatform.model.response.AdminCouponResponse
import com.rita.ecommerceplatform.model.response.CouponPreviewResponse
import com.rita.ecommerceplatform.model.response.PageResponse
import com.rita.ecommerceplatform.model.entity.Coupon
import com.rita.ecommerceplatform.model.entity.CouponDiscountType
import com.rita.ecommerceplatform.model.entity.CouponStatus
import com.rita.ecommerceplatform.repository.database.CouponRepository
import com.rita.ecommerceplatform.repository.database.CouponTerms
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Coupon management for admins (checkpoint 3.1, Step 1) and the customer's preview (Step 2); OrderService applies them at checkout.
 * Delete is soft and final: orders reference their coupon (ON DELETE RESTRICT), and a deleted code is never reused.
 */
@Service
class CouponService(
    private val couponRepository: CouponRepository,
    private val cartService: CartService,
) {

    /**
     * What `code` would save on the customer's cart now, with the same rules as checkout (CouponCheck).
     * Uses the cart's subtotal (items that can be bought); counts no use and locks nothing.
     */
    fun preview(customerId: Long, code: String): CouponPreviewResponse {
        val cart = cartService.getCart(customerId)
        if (cart.items.isEmpty()) throw BadRequestException("Cart is empty")
        val coupon = couponRepository.findByCode(code)
        val discount = CouponCheck.discountFor(coupon, code, cart.subtotal, now())
        return CouponPreviewResponse(
            code = coupon!!.couponCode!!,
            description = coupon.couponDescription,
            subtotal = cart.subtotal,
            discountAmount = discount,
            subtotalAfterDiscount = cart.subtotal - discount,
        )
    }

    /** Newest first; never DELETED. status narrows to ACTIVE or INACTIVE; code matches the start of the code, any case. */
    fun list(page: Int, size: Int, status: String?, code: String?): PageResponse<AdminCouponResponse> {
        val statuses = when (status?.trim()?.uppercase()?.ifEmpty { null }) {
            null -> listOf(CouponStatus.ACTIVE, CouponStatus.INACTIVE)
            "ACTIVE" -> listOf(CouponStatus.ACTIVE)
            "INACTIVE" -> listOf(CouponStatus.INACTIVE)
            else -> throw BadRequestException("status must be ACTIVE or INACTIVE")
        }
        val codeStartsWith = code?.trim()?.ifEmpty { null }
        val safePage = Paging.page(page)
        val safeSize = Paging.size(size)
        val now = now()
        return PageResponse.of(
            couponRepository.search(statuses, codeStartsWith, safeSize, Paging.offset(safePage, safeSize))
                .map { AdminCouponResponse.from(it, now) },
            safePage,
            safeSize,
            couponRepository.count(statuses, codeStartsWith),
        )
    }

    fun get(couponId: Int): AdminCouponResponse = AdminCouponResponse.from(findNotDeleted(couponId), now())

    @Transactional
    fun create(adminId: Int, request: CreateCouponRequest): AdminCouponResponse {
        // Already trimmed during JSON reading (TrimStringDeserializer); non-null after @Valid.
        val code = request.code!!.uppercase()
        val terms = CouponTerms(
            description = request.description.blankToNull(),
            discountType = CouponDiscountType.valueOf(request.discountType!!.uppercase()),
            discountValue = request.discountValue!!,
            maxDiscountAmount = request.maxDiscountAmount,
            minimumOrderValue = request.minimumOrderValue ?: BigDecimal.ZERO,
            usageLimit = request.usageLimit,
            startsAt = request.startsAt,
            expiresAt = request.expiresAt,
        )
        checkTerms(terms)
        request.expiresAt?.let(::checkNotPast)
        if (couponRepository.existsByCode(code)) throw codeInUse()

        val couponId = try {
            couponRepository.insert(code, terms, adminId)
        } catch (e: DuplicateKeyException) {
            // Two admins creating the same code at the same moment: uq__ec_coupon__code stops the second.
            throw codeInUse()
        }
        return get(couponId)
    }

    /**
     * Partial update: fields not sent keep their value, clear... flags remove optional ones, description "" clears it.
     * The rules are checked on the merged values, e.g. a new type against the stored value.
     */
    @Transactional
    fun update(couponId: Int, request: UpdateCouponRequest): AdminCouponResponse {
        // Locked, so a redemption (Step 2) cannot raise the usage count between this check and the update.
        val existing = couponRepository.lockById(couponId)?.takeUnless { it.couponStatus == CouponStatus.DELETED }
            ?: throw couponNotFound()
        val terms = CouponTerms(
            description = if (request.description != null) request.description.blankToNull() else existing.couponDescription,
            discountType = request.discountType?.let { CouponDiscountType.valueOf(it.uppercase()) } ?: existing.couponDiscountType!!,
            discountValue = request.discountValue ?: existing.couponDiscountValue!!,
            maxDiscountAmount = if (request.clearMaxDiscountAmount == true) null else request.maxDiscountAmount ?: existing.couponMaxDiscountAmount,
            minimumOrderValue = request.minimumOrderValue ?: existing.couponMinimumOrderValue!!,
            usageLimit = if (request.clearUsageLimit == true) null else request.usageLimit ?: existing.couponUsageLimit,
            startsAt = if (request.clearStartsAt == true) null else request.startsAt ?: existing.couponStartsAt,
            expiresAt = if (request.clearExpiresAt == true) null else request.expiresAt ?: existing.couponExpiresAt,
        )
        checkTerms(terms)
        // Only a newly sent expiry must be in the future; an expired coupon can still have its other fields edited.
        request.expiresAt?.let(::checkNotPast)
        if (terms.usageLimit != null && terms.usageLimit < existing.couponUsageCount!!) {
            throw BadRequestException("Already used ${existing.couponUsageCount} times, so usageLimit cannot be lower than that")
        }

        couponRepository.update(couponId, terms)
        return get(couponId)
    }

    /** Activate or deactivate. An inactive coupon is refused at checkout but keeps its usage count. */
    @Transactional
    fun updateStatus(couponId: Int, request: UpdateStatusRequest): AdminCouponResponse {
        findNotDeleted(couponId)
        couponRepository.updateStatus(couponId, CouponStatus.valueOf(request.status!!.uppercase()))
        return get(couponId)
    }

    /** Soft delete (final): the row stays for the orders that used it, and its code is never reused. */
    @Transactional
    fun delete(couponId: Int) {
        findNotDeleted(couponId)
        couponRepository.updateStatus(couponId, CouponStatus.DELETED)
    }

    private fun checkTerms(terms: CouponTerms) {
        if (terms.discountType == CouponDiscountType.PERCENTAGE && terms.discountValue > HUNDRED) {
            throw BadRequestException("discountValue must not be more than 100 for a PERCENTAGE coupon")
        }
        if (terms.discountType == CouponDiscountType.FIXED_AMOUNT && terms.maxDiscountAmount != null) {
            throw BadRequestException("maxDiscountAmount is only for PERCENTAGE coupons")
        }
        if (terms.startsAt != null && terms.expiresAt != null && !terms.expiresAt.isAfter(terms.startsAt)) {
            throw BadRequestException("expiresAt must be after startsAt")
        }
    }

    private fun checkNotPast(expiresAt: LocalDateTime) {
        if (!expiresAt.isAfter(now())) throw BadRequestException("expiresAt must be in the future")
    }

    private fun findNotDeleted(couponId: Int): Coupon =
        couponRepository.findById(couponId)?.takeUnless { it.couponStatus == CouponStatus.DELETED } ?: throw couponNotFound()

    private fun couponNotFound() = UnableToLocateRecordException("Coupon not found")

    private fun codeInUse() = RecordAlreadyExistException("Coupon code is already in use")

    private fun String?.blankToNull(): String? = this?.trim()?.ifEmpty { null }

    companion object {
        private val HUNDRED = BigDecimal("100")

        // Platform time (UTC+1), the same clock as SQL_NOW and the coupon dates.
        private val PLATFORM_OFFSET: ZoneOffset = ZoneOffset.ofHours(1)

        fun now(): LocalDateTime = LocalDateTime.now(PLATFORM_OFFSET)
    }
}
