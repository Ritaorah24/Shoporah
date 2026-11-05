package com.rita.ecommerceplatform.repository.api

import com.rita.ecommerceplatform.exception.ApiErrorException
import java.time.OffsetDateTime

/**
 * The payment provider, seen from our side (checkpoint 1.4, P1). PaystackApi is the real one;
 * tests replace it with a fake, so no test ever calls Paystack. Amounts are in kobo (P8).
 * Any failure to talk to the provider is thrown as ApiErrorException (502).
 */
interface PaymentGateway {

    /** Starts a payment and returns the page the customer must open to pay. */
    fun initialize(request: GatewayInitRequest): GatewayInitResult

    /** Asks the provider what happened to a payment. Null when the provider does not know the reference. */
    fun verify(reference: String): GatewayVerifyResult?

    /**
     * Asks the provider to give a successful payment back (checkpoint 2.2). A clear "no" from the provider is
     * Rejected (nothing was sent). A 502 means we do not know whether the provider created the refund:
     * findRefunds tells us later, so a refund is never sent twice blindly.
     */
    fun refund(request: GatewayRefundRequest): GatewayRefundStart

    /** What the provider says about one refund now. Null when the provider does not know the id. */
    fun fetchRefund(providerRefundId: String): GatewayRefund?

    /** The provider's refunds for one payment (found by its transaction id and reference), for a refund it never confirmed. */
    fun findRefunds(transactionId: String?, transactionReference: String): List<GatewayRefund>
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

data class GatewayRefundRequest(
    // The payment's reference (Paystack accepts it in place of the transaction id).
    val transactionReference: String,
    val amountKobo: Long,
    val currency: String,
    val merchantNote: String?,
)

sealed interface GatewayRefundStart {
    data class Accepted(val refund: GatewayRefund) : GatewayRefundStart

    /** The provider refused (e.g. not enough balance, already reversed); `message` is its explanation. */
    data class Rejected(val message: String) : GatewayRefundStart
}

/** PROCESSED and FAILED are final; anything else means the provider is still working on it. */
enum class GatewayRefundStatus { PENDING, PROCESSED, FAILED }

data class GatewayRefund(
    val refundId: String,
    val transactionReference: String?,
    val status: GatewayRefundStatus,
    // The provider's own word for the status (e.g. needs-attention), shown to admins.
    val providerStatus: String?,
    val amountKobo: Long,
    val currency: String?,
    // Why it failed, when the provider says.
    val failureReason: String?,
    // The provider's answer about this refund, kept on the refund row for support.
    val rawResponse: String,
)
