package com.rita.ecommerceplatform.model

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * One row of public.ec_refund: one attempt to give an order's payment back. The amount is in naira, as on the order.
 * providerRefundId is null until the provider confirms it received the refund.
 */
data class Refund(
    val refundId: Long,
    val orderId: Long,
    val paymentId: Long,
    val amount: BigDecimal,
    val currency: String,
    val status: RefundStatus,
    val providerRefundId: String?,
    val providerStatus: String?,
    val reason: String,
    val restock: Boolean,
    val requestedByAdminId: Int,
    val failureReason: String?,
    val processedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Allowed values of ec_refund.refundstatus (ck__ec_refund__status). PROCESSED and FAILED are final. */
enum class RefundStatus { PENDING, PROCESSED, FAILED }
