package com.rita.ecommerceplatform.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * One row of public.ec_refund: one attempt to give an order's payment back. The amount is in naira, as on the order.
 * providerRefundId is null until the provider confirms it received the refund.
 */
data class Refund(
    var refundId: Long? = null,
    var refundOrderId: Long? = null,
    var refundPaymentId: Long? = null,
    var refundAmount: BigDecimal? = null,
    var refundCurrency: String? = null,
    var refundStatus: RefundStatus? = null,
    var refundProviderRefundId: String? = null,
    var refundProviderStatus: String? = null,
    var refundReason: String? = null,
    var refundRestock: Boolean? = null,
    var refundRequestedByAdminId: Int? = null,
    var refundFailureReason: String? = null,
    var refundProcessedAt: LocalDateTime? = null,
    var refundCreatedAt: LocalDateTime? = null,
    var refundUpdatedAt: LocalDateTime? = null,
)

/** Allowed values of ec_refund.refundstatus (ck__ec_refund__status). PROCESSED and FAILED are final. */
enum class RefundStatus { PENDING, PROCESSED, FAILED }
