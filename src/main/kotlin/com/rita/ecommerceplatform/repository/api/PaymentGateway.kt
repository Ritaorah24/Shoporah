package com.rita.ecommerceplatform.repository.api

import java.time.OffsetDateTime

/**
 * The payment provider, seen from our side (checkpoint 1.4, P1). PaystackGateway is the real one;
 * tests replace it with a fake, so no test ever calls Paystack. Amounts are in kobo (P8).
 * Any failure to talk to the provider is thrown as BadGatewayException (502).
 */
interface PaymentGateway {

    /** Starts a payment and returns the page the customer must open to pay. */
    fun initialize(request: GatewayInitRequest): GatewayInitResult

    /** Asks the provider what happened to a payment. Null when the provider does not know the reference. */
    fun verify(reference: String): GatewayVerifyResult?
}

data class GatewayInitRequest(
    val reference: String,
    val email: String,
    val amountKobo: Long,
    val currency: String,
    val callbackUrl: String? = null,
)

data class GatewayInitResult(
    val reference: String,
    val authorizationUrl: String,
    val accessCode: String,
)

enum class GatewayPaymentStatus { SUCCESS, FAILED, ABANDONED, PENDING }

data class GatewayVerifyResult(
    val reference: String,
    val status: GatewayPaymentStatus,
    val amountKobo: Long,
    val currency: String,
    val transactionId: String?,
    val channel: String?,
    val gatewayResponse: String?,
    val paidAt: OffsetDateTime?,
    // The provider's whole response, kept on the payment row for support and disputes.
    val rawResponse: String,
)
