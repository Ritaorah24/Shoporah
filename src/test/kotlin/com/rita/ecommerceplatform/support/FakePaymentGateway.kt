package com.rita.ecommerceplatform.support

import com.rita.ecommerceplatform.exception.ApiErrorException
import com.rita.ecommerceplatform.repository.api.GatewayInitRequest
import com.rita.ecommerceplatform.repository.api.GatewayInitResult
import com.rita.ecommerceplatform.repository.api.GatewayPaymentStatus
import com.rita.ecommerceplatform.repository.api.GatewayRefund
import com.rita.ecommerceplatform.repository.api.GatewayRefundRequest
import com.rita.ecommerceplatform.repository.api.GatewayRefundStart
import com.rita.ecommerceplatform.repository.api.GatewayRefundStatus
import com.rita.ecommerceplatform.repository.api.GatewayVerifyResult
import com.rita.ecommerceplatform.repository.api.PaymentGateway
import com.rita.ecommerceplatform.repository.api.PaystackApi
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Stands in for Paystack in every integration test (imported by IntegrationTestBase, @Primary over
 * PaystackApi), so tests never touch the network. It remembers each initialized payment; a test then
 * decides what "Paystack" says about it with pay / fail / abandon, or makes it unreachable with down.
 * Refunds start "pending"; a test finishes them with processRefund / failRefund, or makes the next start
 * refused (rejectRefund) or unanswered (loseRefundAnswer).
 * State is keyed by payment reference, which is unique per test, so tests sharing the context don't clash.
 */
@TestComponent
@Primary
class FakePaymentGateway : PaymentGateway {

    private val payments = ConcurrentHashMap<String, GatewayVerifyResult>()
    private val initRequests = CopyOnWriteArrayList<GatewayInitRequest>()
    private val verifyRequests = CopyOnWriteArrayList<String>()

    // Refunds by provider id, and how the next refund start for a payment reference behaves.
    private val refunds = ConcurrentHashMap<String, GatewayRefund>()
    private val refundRequests = CopyOnWriteArrayList<GatewayRefundRequest>()
    private val nextRefundStart = ConcurrentHashMap<String, RefundStartMode>()
    private val refundIds = AtomicLong(9_000_000)

    private sealed interface RefundStartMode {
        data class Reject(val message: String) : RefundStartMode

        // Paystack creates the refund, but its answer never reaches us (a timeout).
        data object LoseAnswer : RefundStartMode
    }

    /** While true, every call fails like an unreachable Paystack (502). Reset it in a finally block. */
    @Volatile
    var down = false

    /** Every initialize call received, so a test can check the amount and email that were sent. */
    val initialized: List<GatewayInitRequest> get() = initRequests.toList()

    override fun initialize(request: GatewayInitRequest): GatewayInitResult {
        failIfDown()
        initRequests += request
        payments[request.reference] = result(request.reference, GatewayPaymentStatus.PENDING, request.amountKobo, request.currency)
        return GatewayInitResult(request.reference, "https://checkout.example.test/${request.reference}", "access-${request.reference}")
    }

    override fun verify(reference: String): GatewayVerifyResult? {
        failIfDown()
        verifyRequests += reference
        return payments[reference]
    }

    /** How many times "Paystack" was asked about this reference, so a test can check it was not asked again. */
    fun verifyCalls(reference: String): Int = verifyRequests.count { it == reference }

    /** Paystack now says the payment succeeded; amount and currency can be overridden to test tampering. */
    fun pay(reference: String, amountKobo: Long? = null, currency: String? = null) =
        update(reference, GatewayPaymentStatus.SUCCESS, amountKobo, currency)

    fun fail(reference: String) = update(reference, GatewayPaymentStatus.FAILED)

    fun abandon(reference: String) = update(reference, GatewayPaymentStatus.ABANDONED)

    override fun refund(request: GatewayRefundRequest): GatewayRefundStart {
        failIfDown()
        refundRequests += request
        val mode = nextRefundStart.remove(request.transactionReference)
        if (mode is RefundStartMode.Reject) return GatewayRefundStart.Rejected(mode.message)
        val refund = refundResult(
            "${refundIds.incrementAndGet()}", request.transactionReference, "pending", request.amountKobo, request.currency,
        )
        refunds[refund.refundId] = refund
        if (mode == RefundStartMode.LoseAnswer) throw ApiErrorException(PaystackApi.PROVIDER_ERROR)
        return GatewayRefundStart.Accepted(refund)
    }

    override fun fetchRefund(providerRefundId: String): GatewayRefund? {
        failIfDown()
        return refunds[providerRefundId]
    }

    override fun findRefunds(transactionId: String?, transactionReference: String): List<GatewayRefund> {
        failIfDown()
        return refunds.values.filter { it.transactionReference == transactionReference }.sortedBy { it.refundId }
    }

    /** Every refund start "Paystack" received for this payment reference (refused and unanswered ones too). */
    fun refundRequests(reference: String): List<GatewayRefundRequest> = refundRequests.filter { it.transactionReference == reference }

    /** The next refund start for this payment is refused with `message`; nothing is created. */
    fun rejectRefund(reference: String, message: String) {
        nextRefundStart[reference] = RefundStartMode.Reject(message)
    }

    /** The next refund start for this payment is created at "Paystack", but its answer is lost (a 502 for us). */
    fun loseRefundAnswer(reference: String) {
        nextRefundStart[reference] = RefundStartMode.LoseAnswer
    }

    /** Paystack now says this payment's pending refunds are processed (the money went back). */
    fun processRefund(reference: String) = updateRefunds(reference, "processed")

    fun failRefund(reference: String, reason: String? = null) = updateRefunds(reference, "failed", reason)

    /** Paystack needs the customer's account details: still in progress. */
    fun refundNeedsAttention(reference: String) = updateRefunds(reference, "needs-attention")

    private fun updateRefunds(reference: String, status: String, reason: String? = null) {
        val pending = refunds.values.filter { it.transactionReference == reference && it.status == GatewayRefundStatus.PENDING }
        check(pending.isNotEmpty()) { "No pending refund was started for payment $reference" }
        pending.forEach { refunds[it.refundId] = refundResult(it.refundId, reference, status, it.amountKobo, it.currency, reason) }
    }

    private fun refundResult(id: String, reference: String, status: String, amountKobo: Long, currency: String?, reason: String? = null) =
        GatewayRefund(
            refundId = id,
            transactionReference = reference,
            status = PaystackApi.refundStatusOf(status),
            providerStatus = status,
            amountKobo = amountKobo,
            currency = currency,
            failureReason = if (status == "failed") reason else null,
            rawResponse = """{"id":$id,"transaction_reference":"$reference","status":"$status","amount":$amountKobo}""",
        )

    private fun update(reference: String, status: GatewayPaymentStatus, amountKobo: Long? = null, currency: String? = null) {
        val current = payments[reference] ?: error("No payment was initialized with reference $reference")
        payments[reference] = result(reference, status, amountKobo ?: current.amountKobo, currency ?: current.currency)
    }

    private fun result(reference: String, status: GatewayPaymentStatus, amountKobo: Long, currency: String) =
        GatewayVerifyResult(
            reference = reference,
            status = status,
            amountKobo = amountKobo,
            currency = currency,
            transactionId = if (status == GatewayPaymentStatus.PENDING) null else "fake-$reference",
            channel = if (status == GatewayPaymentStatus.SUCCESS) "card" else null,
            gatewayResponse = status.name.lowercase(),
            paidAt = null,
            rawResponse = """{"status":true,"data":{"reference":"$reference","status":"${status.name.lowercase()}"}}""",
        )

    private fun failIfDown() {
        if (down) throw ApiErrorException(PaystackApi.PROVIDER_ERROR)
    }
}
