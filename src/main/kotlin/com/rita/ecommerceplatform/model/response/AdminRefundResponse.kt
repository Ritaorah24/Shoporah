package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Refund
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * One refund attempt, as admins see it on the order. A PENDING refund with providerRefundId null has not been
 * confirmed by the provider yet: POST /{stage}/admin/refunds/{refundId}/refresh finds out.
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
            refundId = refund.refundId!!,
            status = refund.refundStatus!!.name,
            amount = refund.refundAmount!!,
            currency = refund.refundCurrency!!,
            reason = refund.refundReason!!,
            restock = refund.refundRestock!!,
            requestedByAdminId = refund.refundRequestedByAdminId!!,
            providerRefundId = refund.refundProviderRefundId,
            providerStatus = refund.refundProviderStatus,
            failureReason = refund.refundFailureReason,
            processedAt = refund.refundProcessedAt,
            createdAt = refund.refundCreatedAt!!,
        )
    }
}
