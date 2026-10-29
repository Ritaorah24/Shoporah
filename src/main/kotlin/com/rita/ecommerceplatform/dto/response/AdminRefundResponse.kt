package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.Refund
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * One refund attempt, as admins see it on the order. A PENDING refund with providerRefundId null has not been
 * confirmed by the provider yet: POST /api/admin/refunds/{refundId}/refresh finds out.
 * providerStatus is the provider's own word (e.g. needs-attention); failureReason says why a FAILED one failed.
 */
data class AdminRefundResponse(
    val refundId: Long,
    val status: String,
    val amount: BigDecimal,
    val currency: String,
    val reason: String,
    val restock: Boolean,
    val requestedByAdminId: Int,
    val providerRefundId: String?,
    val providerStatus: String?,
    val failureReason: String?,
    val processedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(refund: Refund) = AdminRefundResponse(
            refundId = refund.refundId,
            status = refund.status.name,
            amount = refund.amount,
            currency = refund.currency,
            reason = refund.reason,
            restock = refund.restock,
            requestedByAdminId = refund.requestedByAdminId,
            providerRefundId = refund.providerRefundId,
            providerStatus = refund.providerStatus,
            failureReason = refund.failureReason,
            processedAt = refund.processedAt,
            createdAt = refund.createdAt,
        )
    }
}
