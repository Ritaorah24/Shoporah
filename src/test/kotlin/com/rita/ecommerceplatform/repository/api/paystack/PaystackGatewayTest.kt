package com.rita.ecommerceplatform.repository.api.paystack

import com.rita.ecommerceplatform.exception.BadGatewayException
import com.rita.ecommerceplatform.repository.api.GatewayInitRequest
import com.rita.ecommerceplatform.repository.api.GatewayInitResult
import com.rita.ecommerceplatform.repository.api.GatewayPaymentStatus
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * PaystackGateway against a mocked Paystack (checkpoint 1.4, Step 1): the requests it sends and how it
 * reads the answers. No Spring context and no network; the answers are shaped like Paystack's documented ones.
 */
class PaystackGatewayTest {

    private val builder = RestClient.builder().baseUrl("https://api.paystack.co")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val gateway = PaystackGateway(builder.build(), JsonMapper.builder().build())

    private fun json(body: String) = withSuccess(body, MediaType.APPLICATION_JSON)

    private fun withError(status: HttpStatus, body: String) =
        withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body)

    private val initRequest = GatewayInitRequest(
        reference = "PAY-TEST-1",
        email = "ada@example.com",
        amountKobo = 1_250_050,
        currency = "NGN",
    )

    @Test
    fun `initialize sends the reference, email, amount in kobo and currency, and returns the payment page`() {
        server.expect(requestTo("https://api.paystack.co/transaction/initialize"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json("""{"reference":"PAY-TEST-1","email":"ada@example.com","amount":1250050,"currency":"NGN"}""", true),
            )
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
        server.expect(requestTo("https://api.paystack.co/transaction/initialize"))
            .andExpect(content().json("""{"callback_url":"https://shop.example.com/paid"}"""))
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
        server.expect(requestTo("https://api.paystack.co/transaction/verify/PAY-TEST-1"))
            .andExpect(method(HttpMethod.GET))
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
        assertEquals(GatewayPaymentStatus.SUCCESS, PaystackGateway.statusOf("success"))
        assertEquals(GatewayPaymentStatus.FAILED, PaystackGateway.statusOf("failed"))
        assertEquals(GatewayPaymentStatus.FAILED, PaystackGateway.statusOf("reversed"))
        assertEquals(GatewayPaymentStatus.ABANDONED, PaystackGateway.statusOf("abandoned"))
        listOf("ongoing", "pending", "processing", "queued", null).forEach {
            assertEquals(GatewayPaymentStatus.PENDING, PaystackGateway.statusOf(it))
        }
    }

    @Test
    fun `verify of a payment the customer has not finished has no paid date`() {
        server.expect(requestTo("https://api.paystack.co/transaction/verify/PAY-TEST-1"))
            .andRespond(
                json("""{"status":true,"data":{"id":1,"status":"abandoned","reference":"PAY-TEST-1","amount":1250050,"currency":"NGN","paid_at":null}}"""),
            )

        val result = gateway.verify("PAY-TEST-1")!!

        assertEquals(GatewayPaymentStatus.ABANDONED, result.status)
        assertNull(result.paidAt)
    }

    @Test
    fun `verify of a reference Paystack does not know returns null`() {
        server.expect(requestTo("https://api.paystack.co/transaction/verify/NOPE"))
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
            withSuccess("<html>gateway timeout</html>", MediaType.TEXT_HTML),
            withException(IOException("Connection reset")),
        )
        failures.forEach { response ->
            server.reset()
            server.expect(requestTo("https://api.paystack.co/transaction/verify/PAY-TEST-1")).andRespond(response)

            val ex = assertFailsWith<BadGatewayException> { gateway.verify("PAY-TEST-1") }
            assertEquals(HttpStatus.BAD_GATEWAY, ex.status)
            assertEquals(PaystackGateway.PROVIDER_ERROR, ex.message)
        }
    }

    @Test
    fun `initialize failures become a 502 too`() {
        server.expect(requestTo("https://api.paystack.co/transaction/initialize"))
            .andRespond(withError(HttpStatus.BAD_REQUEST, """{"status":false,"message":"Duplicate Transaction Reference"}"""))

        assertFailsWith<BadGatewayException> { gateway.initialize(initRequest) }
    }
}
