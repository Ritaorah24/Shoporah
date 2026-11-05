package com.rita.ecommerceplatform.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/** One row of public.ec_payment: one attempt to pay for an order. The amount is in naira, as on the order. */
data class Payment(
    val paymentId: Long,
    val orderId: Long,
    val reference: String,
    val provider: String,
    val providerTransactionId: String?,
    val amount: BigDecimal,
    val currency: String,
    val status: PaymentStatus,
    val authorizationUrl: String?,
    val channel: String?,
    val gatewayResponse: String?,
    val paidAt: LocalDateTime?,
    val verifiedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Allowed values of ec_payment.paymentstatus (ck__ec_payment__status). */
enum class PaymentStatus { PENDING, SUCCESS, FAILED, ABANDONED }
