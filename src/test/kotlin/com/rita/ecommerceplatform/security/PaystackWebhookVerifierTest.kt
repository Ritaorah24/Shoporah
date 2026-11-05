package com.rita.ecommerceplatform.security

import com.rita.ecommerceplatform.config.PaystackProps
import com.rita.ecommerceplatform.model.request.paystack.PaystackWebhookEvent
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The webhook signature check and body reading (checkpoint 1.4, Step 5). No Spring context. The expected
 * signature was computed outside the app with: printf '%s' '<body>' | openssl dgst -sha512 -hmac 'sk_test_example'
 */
class PaystackWebhookVerifierTest {

    private val verifier = PaystackWebhookVerifier(PaystackProps(secretKey = "sk_test_example", baseUrl = ""))
    private val body = """{"event":"charge.success","data":{"reference":"PAY-1"}}""".toByteArray()
    private val signature =
        "ed9c4eb583679f94c4e41cf7930da0362269924b95d7f124e5314c63f355118d98a63508fc5d661f73d1b8382da724d34e809a5d2c9a365efe4a3dad411f33b3"

    @Test
    fun `accepts Paystack's signature of the exact body`() {
        assertTrue(verifier.isValid(body, signature))
        assertTrue(verifier.isValid(body, signature.uppercase()))
    }

    @Test
    fun `rejects a missing or wrong signature, a changed body and another key`() {
        assertFalse(verifier.isValid(body, null))
        assertFalse(verifier.isValid(body, ""))
        assertFalse(verifier.isValid(body, "0".repeat(128)))
        assertFalse(verifier.isValid("""{"event":"charge.success","data":{"reference":"PAY-2"}}""".toByteArray(), signature))
        // Same content, different spacing: different bytes, so a re-written body does not pass either.
        assertFalse(verifier.isValid("""{"event": "charge.success","data":{"reference":"PAY-1"}}""".toByteArray(), signature))
        assertFalse(PaystackWebhookVerifier(PaystackProps(secretKey = "sk_test_other", baseUrl = "")).isValid(body, signature))
    }

    @Test
    fun `reads the event and reference, and nothing from an unreadable body`() {
        val jsonMapper = JsonMapper.builder().build()
        assertEquals(PaystackWebhookEvent("charge.success", "PAY-1"), PaystackWebhookEvent.parse(jsonMapper, body))
        assertTrue(PaystackWebhookEvent.parse(jsonMapper, body)!!.isChargeSuccess)
        assertEquals(
            PaystackWebhookEvent("transfer.success", null),
            PaystackWebhookEvent.parse(jsonMapper, """{"event":"transfer.success","data":{}}""".toByteArray()),
        )
        assertNull(PaystackWebhookEvent.parse(jsonMapper, "not json".toByteArray()))
        assertNull(PaystackWebhookEvent.parse(jsonMapper, """{"data":{"reference":"PAY-1"}}""".toByteArray()))
    }

    @Test
    fun `reads a refund event's refund id and payment reference`() {
        val jsonMapper = JsonMapper.builder().build()
        val processed = PaystackWebhookEvent.parse(
            jsonMapper,
            """{"event":"refund.processed","data":{"id":5017041,"transaction_reference":"PAY-1","status":"processed","amount":400000}}"""
                .toByteArray(),
        )!!
        assertEquals(PaystackWebhookEvent("refund.processed", null, refundId = "5017041", transactionReference = "PAY-1"), processed)
        assertTrue(processed.isRefundEvent)
        assertTrue(processed.isRefundFinal)
        assertFalse(processed.isChargeSuccess)

        assertTrue(PaystackWebhookEvent.parse(jsonMapper, """{"event":"refund.failed","data":{"id":"7"}}""".toByteArray())!!.isRefundFinal)
        val pending = PaystackWebhookEvent.parse(jsonMapper, """{"event":"refund.pending","data":{}}""".toByteArray())!!
        assertEquals(PaystackWebhookEvent("refund.pending", null), pending)
        assertTrue(pending.isRefundEvent)
        assertFalse(pending.isRefundFinal)
        assertFalse(PaystackWebhookEvent.parse(jsonMapper, body)!!.isRefundEvent)
    }
}
