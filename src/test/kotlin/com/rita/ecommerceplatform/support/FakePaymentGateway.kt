package com.rita.ecommerceplatform.support

import com.rita.ecommerceplatform.exception.BadGatewayException
import com.rita.ecommerceplatform.repository.api.GatewayInitRequest
import com.rita.ecommerceplatform.repository.api.GatewayInitResult
import com.rita.ecommerceplatform.repository.api.GatewayPaymentStatus
import com.rita.ecommerceplatform.repository.api.GatewayVerifyResult
import com.rita.ecommerceplatform.repository.api.PaymentGateway
import com.rita.ecommerceplatform.repository.api.paystack.PaystackGateway
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for Paystack in every integration test (imported by IntegrationTestBase, @Primary over
 * PaystackGateway), so tests never touch the network. It remembers each initialized payment; a test then
 * decides what "Paystack" says about it with pay / fail / abandon, or makes it unreachable with down.
 * State is keyed by payment reference, which is unique per test, so tests sharing the context don't clash.
 */
@TestComponent
@Primary
class FakePaymentGateway : PaymentGateway {

    private val payments = ConcurrentHashMap<String, GatewayVerifyResult>()
    private val initRequests = CopyOnWriteArrayList<GatewayInitRequest>()
    private val verifyRequests = CopyOnWriteArrayList<String>()

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
        if (down) throw BadGatewayException(PaystackGateway.PROVIDER_ERROR)
    }
}
