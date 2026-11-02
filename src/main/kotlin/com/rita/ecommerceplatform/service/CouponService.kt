package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.dto.request.CreateCouponRequest
import com.rita.ecommerceplatform.dto.request.UpdateCouponRequest
import com.rita.ecommerceplatform.dto.request.UpdateStatusRequest
import com.rita.ecommerceplatform.dto.response.AdminCouponResponse
import com.rita.ecommerceplatform.dto.response.PageResponse
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.Coupon
import com.rita.ecommerceplatform.model.CouponDiscountType
import com.rita.ecommerceplatform.model.CouponStatus
import com.rita.ecommerceplatform.repository.CouponRepository
import com.rita.ecommerceplatform.repository.CouponTerms
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Coupon management for admins (checkpoint 3.1, Step 1). Customers use coupons at checkout from Step 2.
 * Delete is soft and final: orders reference their coupon (ON DELETE RESTRICT), and a deleted code is never reused.
 */
@Service
class CouponService(private val couponRepository: CouponRepository) {

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
        val existing = couponRepository.lockById(couponId)?.takeUnless { it.status == CouponStatus.DELETED }
            ?: throw couponNotFound()
        val terms = CouponTerms(
            description = if (request.description != null) request.description.blankToNull() else existing.description,
            discountType = request.discountType?.let { CouponDiscountType.valueOf(it.uppercase()) } ?: existing.discountType,
            discountValue = request.discountValue ?: existing.discountValue,
            maxDiscountAmount = if (request.clearMaxDiscountAmount == true) null else request.maxDiscountAmount ?: existing.maxDiscountAmount,
            minimumOrderValue = request.minimumOrderValue ?: existing.minimumOrderValue,
            usageLimit = if (request.clearUsageLimit == true) null else request.usageLimit ?: existing.usageLimit,
            startsAt = if (request.clearStartsAt == true) null else request.startsAt ?: existing.startsAt,
            expiresAt = if (request.clearExpiresAt == true) null else request.expiresAt ?: existing.expiresAt,
        )
        checkTerms(terms)
        // Only a newly sent expiry must be in the future; an expired coupon can still have its other fields edited.
        request.expiresAt?.let(::checkNotPast)
        if (terms.usageLimit != null && terms.usageLimit < existing.usageCount) {
            throw ConflictException("Already used ${existing.usageCount} times, so usageLimit cannot be lower than that")
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
        couponRepository.findById(couponId)?.takeUnless { it.status == CouponStatus.DELETED } ?: throw couponNotFound()

    private fun couponNotFound() = NotFoundException("Coupon not found")

    private fun codeInUse() = ConflictException("Coupon code is already in use")

    private fun String?.blankToNull(): String? = this?.trim()?.ifEmpty { null }

    companion object {
        private val HUNDRED = BigDecimal("100")

        // Platform time (UTC+1), the same clock as SQL_NOW and the coupon dates.
        private val PLATFORM_OFFSET: ZoneOffset = ZoneOffset.ofHours(1)

        fun now(): LocalDateTime = LocalDateTime.now(PLATFORM_OFFSET)
    }
}
