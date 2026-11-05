package com.rita.ecommerceplatform.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime

/** One row of public.ec_payment: one attempt to pay for an order. The amount is in naira, as on the order. */
data class Payment(
    var paymentId: Long? = null,
    var paymentOrderId: Long? = null,
    var paymentReference: String? = null,
    var paymentProvider: String? = null,
    var paymentProviderTransactionId: String? = null,
    var paymentAmount: BigDecimal? = null,
    var paymentCurrency: String? = null,
    var paymentStatus: PaymentStatus? = null,
    var paymentAuthorizationUrl: String? = null,
    var paymentChannel: String? = null,
    var paymentGatewayResponse: String? = null,
    var paymentPaidAt: LocalDateTime? = null,
    var paymentVerifiedAt: LocalDateTime? = null,
    var paymentCreatedAt: LocalDateTime? = null,
    var paymentUpdatedAt: LocalDateTime? = null,
)

/** Allowed values of ec_payment.paymentstatus (ck__ec_payment__status). */
enum class PaymentStatus { PENDING, SUCCESS, FAILED, ABANDONED }
