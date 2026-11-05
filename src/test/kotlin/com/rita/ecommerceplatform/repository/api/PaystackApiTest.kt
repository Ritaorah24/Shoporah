package com.rita.ecommerceplatform.repository.api

import com.rita.ecommerceplatform.config.PaystackProps
import com.rita.ecommerceplatform.exception.ApiErrorException
import com.rita.ecommerceplatform.support.MockHttpServer
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * PaystackApi against a mocked Paystack (checkpoint 1.4, Step 1): the requests it sends and how it
 * reads the answers. No Spring context and no network; the answers are shaped like Paystack's documented ones.
 */
class PaystackApiTest {

    private val server = MockHttpServer()
    private val gateway = PaystackApi(
        server.client,
        PaystackProps(secretKey = "sk_test_example", baseUrl = "https://api.paystack.co"),
        JsonMapper.builder().build(),
    )

    private fun json(body: String) = MockHttpServer.Answer(body = body)

    private fun withError(status: HttpStatus, body: String) = MockHttpServer.Answer(status = status.value(), body = body)

    private val initRequest = GatewayInitRequest(
        reference = "PAY-TEST-1",
        email = "ada@example.com",
        amountKobo = 1_250_050,
        currency = "NGN",
    )

    @Test
    fun `initialize sends the reference, email, amount in kobo and currency, and returns the payment page`() {
        server.expect("https://api.paystack.co/transaction/initialize")
            .andExpectMethod("POST")
            .andExpectJson("""{"reference":"PAY-TEST-1","email":"ada@example.com","amount":1250050,"currency":"NGN"}""", strict = true)
            .andRespond(
                json(
                    """
                    {"status":true,"message":"Authorization URL created","data":{
                      "authorization_url":"https://checkout.paystack.com/abc123","access_code":"abc123","reference":"PAY-TEST-1"}}
                    """.trimIndent(),
                ),
            )

        val result = gateway.initialize(initRequest)

        assertEquals(GatewayInitResult("PAY-TEST-1", "https://checkout.paystack.com/abc123", "abc123"), result)
        server.verify()
    }

    @Test
    fun `initialize sends the callback URL only when there is one`() {
        server.expect("https://api.paystack.co/transaction/initialize")
            .andExpectJson("""{"callback_url":"https://shop.example.com/paid"}""")
            .andRespond(json("""{"status":true,"data":{"authorization_url":"https://checkout.paystack.com/x","access_code":"x"}}"""))

        gateway.initialize(initRequest.copy(callbackUrl = "https://shop.example.com/paid"))
        server.verify()
    }

    @Test
    fun `verify reads a successful payment`() {
        val body = """
            {"status":true,"message":"Verification successful","data":{
              "id":4099260516,"status":"success","reference":"PAY-TEST-1","amount":1250050,"currency":"NGN",
              "channel":"card","gateway_response":"Successful","paid_at":"2026-10-22T09:15:30.000Z"}}
        """.trimIndent()
        server.expect("https://api.paystack.co/transaction/verify/PAY-TEST-1")
            .andExpectMethod("GET")
            .andRespond(json(body))

        val result = gateway.verify("PAY-TEST-1")!!

        assertEquals(GatewayPaymentStatus.SUCCESS, result.status)
        assertEquals("PAY-TEST-1", result.reference)
        assertEquals(1_250_050, result.amountKobo)
        assertEquals("NGN", result.currency)
        assertEquals("4099260516", result.transactionId)
        assertEquals("card", result.channel)
        assertEquals("Successful", result.gatewayResponse)
        assertEquals(OffsetDateTime.parse("2026-10-22T09:15:30Z"), result.paidAt)
        assertEquals(body, result.rawResponse)
    }

    @Test
    fun `Paystack statuses map to ours`() {
        assertEquals(GatewayPaymentStatus.SUCCESS, PaystackApi.statusOf("success"))
        assertEquals(GatewayPaymentStatus.FAILED, PaystackApi.statusOf("failed"))
        assertEquals(GatewayPaymentStatus.FAILED, PaystackApi.statusOf("reversed"))
        assertEquals(GatewayPaymentStatus.ABANDONED, PaystackApi.statusOf("abandoned"))
        listOf("ongoing", "pending", "processing", "queued", null).forEach {
            assertEquals(GatewayPaymentStatus.PENDING, PaystackApi.statusOf(it))
        }
    }

    @Test
    fun `verify of a payment the customer has not finished has no paid date`() {
        server.expect("https://api.paystack.co/transaction/verify/PAY-TEST-1")
            .andRespond(
                json("""{"status":true,"data":{"id":1,"status":"abandoned","reference":"PAY-TEST-1","amount":1250050,"currency":"NGN","paid_at":null}}"""),
            )

        val result = gateway.verify("PAY-TEST-1")!!

        assertEquals(GatewayPaymentStatus.ABANDONED, result.status)
        assertNull(result.paidAt)
    }

    @Test
    fun `a reference from the customer's URL stays one encoded path segment`() {
        server.expect("https://api.paystack.co/transaction/verify/A%2FB%20C%3Fx=1")
            .andRespond(withError(HttpStatus.BAD_REQUEST, """{"status":false,"code":"transaction_not_found"}"""))

        assertNull(gateway.verify("A/B C?x=1"))
        server.verify()
    }

    @Test
    fun `verify of a reference Paystack does not know returns null`() {
        server.expect("https://api.paystack.co/transaction/verify/NOPE")
            .andRespond(
                withError(
                    HttpStatus.BAD_REQUEST,
                    """{"status":false,"message":"Transaction reference not found","type":"validation_error","code":"transaction_not_found"}""",
                ),
            )

        assertNull(gateway.verify("NOPE"))
    }

    @Test
    fun `Paystack errors, bad answers and network failures become a 502`() {
        val failures = listOf(
            withError(HttpStatus.INTERNAL_SERVER_ERROR, """{"status":false,"message":"Server error"}"""),
            withError(HttpStatus.UNAUTHORIZED, """{"status":false,"message":"Invalid key"}"""),
            json("""{"status":false,"message":"Something went wrong"}"""),
            json("""{"status":true,"data":{"reference":"PAY-TEST-1"}}"""),
            MockHttpServer.Answer(body = "<html>gateway timeout</html>", contentType = "text/html"),
            MockHttpServer.Answer(failure = IOException("Connection reset")),
        )
        failures.forEach { response ->
            server.reset()
            server.expect("https://api.paystack.co/transaction/verify/PAY-TEST-1").andRespond(response)

            val ex = assertFailsWith<ApiErrorException> { gateway.verify("PAY-TEST-1") }
            assertEquals(PaystackApi.PROVIDER_ERROR, ex.message)
        }
    }

    @Test
    fun `initialize failures become a 502 too`() {
        server.expect("https://api.paystack.co/transaction/initialize")
            .andRespond(withError(HttpStatus.BAD_REQUEST, """{"status":false,"message":"Duplicate Transaction Reference"}"""))

        assertFailsWith<ApiErrorException> { gateway.initialize(initRequest) }
    }

    private val refundRequest = GatewayRefundRequest(
        transactionReference = "PAY-TEST-1",
        amountKobo = 400_000,
        currency = "NGN",
        merchantNote = "Item damaged",
    )

    @Test
    fun `refund sends the payment reference, amount in kobo, currency and note, and reads the pending refund`() {
        server.expect("https://api.paystack.co/refund")
            .andExpectMethod("POST")
            .andExpectJson("""{"transaction":"PAY-TEST-1","amount":400000,"currency":"NGN","merchant_note":"Item damaged"}""", strict = true)
            .andRespond(
                json(
                    """
                    {"status":true,"message":"Refund has been queued for processing","data":{
                      "transaction":{"id":3298598423,"reference":"PAY-TEST-1","amount":400000,"currency":"NGN"},
                      "integration":412829,"deducted_amount":0,"channel":null,"merchant_note":"Item damaged","customer_note":"",
                      "status":"pending","refunded_by":"admin@example.com","expected_at":"2026-11-05T10:00:00.000Z",
                      "currency":"NGN","domain":"test","amount":400000,"fully_deducted":false,"id":5017041}}
                    """.trimIndent(),
                ),
            )

        val start = gateway.refund(refundRequest)

        val refund = (start as GatewayRefundStart.Accepted).refund
        assertEquals("5017041", refund.refundId)
        assertEquals("PAY-TEST-1", refund.transactionReference)
        assertEquals(GatewayRefundStatus.PENDING, refund.status)
        assertEquals("pending", refund.providerStatus)
        assertEquals(400_000, refund.amountKobo)
        assertNull(refund.failureReason)
        server.verify()
    }

    @Test
    fun `a refund Paystack refuses is Rejected with its message, other failures are a 502`() {
        server.expect("https://api.paystack.co/refund")
            .andRespond(withError(HttpStatus.BAD_REQUEST, """{"status":false,"message":"Transaction has been fully reversed"}"""))
        assertEquals(GatewayRefundStart.Rejected("Transaction has been fully reversed"), gateway.refund(refundRequest))

        // Not about this refund (our key, rate limit, Paystack down, network): we cannot tell whether it was created.
        listOf(
            withError(HttpStatus.UNAUTHORIZED, """{"status":false,"message":"Invalid key"}"""),
            withError(HttpStatus.TOO_MANY_REQUESTS, """{"status":false,"message":"Slow down"}"""),
            withError(HttpStatus.INTERNAL_SERVER_ERROR, """{"status":false,"message":"Server error"}"""),
            withError(HttpStatus.BAD_REQUEST, "<html>bad gateway</html>"),
            MockHttpServer.Answer(failure = IOException("Read timed out")),
        ).forEach { response ->
            server.reset()
            server.expect("https://api.paystack.co/refund").andRespond(response)
            assertFailsWith<ApiErrorException> { gateway.refund(refundRequest) }
        }
    }

    @Test
    fun `fetchRefund reads a processed or failed refund, and an unknown id is null`() {
        server.expect("https://api.paystack.co/refund/5017041")
            .andExpectMethod("GET")
            .andRespond(
                json(
                    """
                    {"status":true,"message":"Refund retrieved","data":{"integration":412829,"transaction":3298598423,
                      "id":5017041,"domain":"test","currency":"NGN","amount":400000,"status":"processed",
                      "refunded_at":"2026-11-02T09:00:00.000Z","transaction_reference":"PAY-TEST-1","reason":""}}
                    """.trimIndent(),
                ),
            )
        val processed = gateway.fetchRefund("5017041")!!
        assertEquals(GatewayRefundStatus.PROCESSED, processed.status)
        assertEquals("PAY-TEST-1", processed.transactionReference)
        assertNull(processed.failureReason)

        server.reset()
        server.expect("https://api.paystack.co/refund/5017041")
            .andRespond(
                json("""{"status":true,"data":{"id":5017041,"amount":400000,"status":"failed","reason":"Account closed"}}"""),
            )
        val failed = gateway.fetchRefund("5017041")!!
        assertEquals(GatewayRefundStatus.FAILED, failed.status)
        assertEquals("Account closed", failed.failureReason)

        server.reset()
        server.expect("https://api.paystack.co/refund/1")
            .andRespond(withError(HttpStatus.NOT_FOUND, """{"status":false,"message":"Refund not found"}"""))
        assertNull(gateway.fetchRefund("1"))
    }

    @Test
    fun `findRefunds asks by transaction id and keeps only this payment's refunds`() {
        server.expect("https://api.paystack.co/refund?perPage=100&transaction=3298598423")
            .andExpectMethod("GET")
            .andRespond(
                json(
                    """
                    {"status":true,"message":"Refunds retrieved","data":[
                      {"id":5017041,"transaction":3298598423,"amount":400000,"status":"processing","transaction_reference":"PAY-TEST-1"},
                      {"id":5017999,"transaction":1111111111,"amount":900000,"status":"processed","transaction_reference":"PAY-OTHER"}
                    ],"meta":{"total":2}}
                    """.trimIndent(),
                ),
            )

        val refunds = gateway.findRefunds("3298598423", "PAY-TEST-1")

        assertEquals(listOf("5017041"), refunds.map { it.refundId })
        assertEquals(GatewayRefundStatus.PENDING, refunds.single().status)
        assertEquals("processing", refunds.single().providerStatus)
        server.verify()
    }

    @Test
    fun `Paystack refund statuses map to ours`() {
        assertEquals(GatewayRefundStatus.PROCESSED, PaystackApi.refundStatusOf("processed"))
        assertEquals(GatewayRefundStatus.FAILED, PaystackApi.refundStatusOf("failed"))
        listOf("pending", "processing", "needs-attention", null).forEach {
            assertEquals(GatewayRefundStatus.PENDING, PaystackApi.refundStatusOf(it))
        }
    }
}
