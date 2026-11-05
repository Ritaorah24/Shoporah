package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Refund events on the Paystack webhook (checkpoint 2.2, Step 5b). Bodies are signed here with the configured
 * secret key, as Paystack signs them; what Paystack says about the refund comes from the fake, as in RefundTest.
 */
class RefundWebhookTest : IntegrationTestBase() {

    @Value("\${paystack.secret-key}")
    private lateinit var secretKey: String

    private lateinit var adminToken: String
    private lateinit var email: String
    private lateinit var token: String
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        email = uniqueEmail()
        registerCustomer(email)
        token = loginCustomer(email)
        categoryId = createCategory()
        createAddress()
    }

    private data class Paid(val orderId: Long, val reference: String, val paymentReference: String)

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createCategory(): Int {
        val result = mockMvc.post("/dev/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun createProduct(): Long {
        val result = mockMvc.post("/dev/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to unique("Phone"),
                "sku" to unique("sku"),
                "categoryId" to categoryId,
                "price" to 1000,
                "stockQuantity" to 10,
            )
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["productId"].asLong()
    }

    private fun createAddress() {
        mockMvc.post("/dev/customer/addresses") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "recipientName" to "Ada Obi",
                "recipientPhoneNumber" to "08012345678",
                "addressLine1" to "12 Allen Avenue",
                "city" to "Ikeja",
                "state" to "Lagos",
                "country" to "Nigeria",
            )
        }.andExpect { responseCode("00") }
    }

    /** A paid order (2 of one product, ₦2,000.00), then cancelled by an admin: it is waiting for its refund. */
    private fun cancelledPaidOrder(): Paid {
        val productId = createProduct()
        mockMvc.post("/dev/customer/cart/items") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to productId, "quantity" to 2)
        }.andExpect { responseCode("00") }
        val order = readJson(
            mockMvc.post("/dev/customer/orders") {
                header("Authorization", "Bearer $token")
                header("Idempotency-Key", UUID.randomUUID().toString())
            }.andExpect { responseCode("00") }.andReturn(),
        )
        val orderId = order["orderId"].asLong()
        val paymentReference = readJson(
            mockMvc.post("/dev/customer/payments/initialize") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = json("orderReference" to order["reference"].asString())
            }.andExpect { responseCode("00") }.andReturn(),
        )["paymentReference"].asString()
        paymentGateway.pay(paymentReference)
        mockMvc.get("/dev/customer/payments/verify/$paymentReference") { header("Authorization", "Bearer $token") }
            .andExpect { responseCode("00") }
        mockMvc.post("/dev/admin/orders/$orderId/cancel") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("reason" to "Item damaged")
        }.andExpect { responseCode("00") }
        return Paid(orderId, order["reference"].asString(), paymentReference)
    }

    private fun startRefund(orderId: Long): JsonNode = readJson(
        mockMvc.post("/dev/admin/orders/$orderId/refund") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("reason" to "Item damaged")
        }.andExpect { responseCode("00") }.andReturn(),
    )["refunds"][0]

    /** A body shaped like Paystack's; the status and amount in it are never trusted by the app. */
    private fun event(event: String, refundId: String?, paymentReference: String, status: String = "processed") =
        """{"event":"$event","data":{"id":${refundId ?: "null"},"transaction_reference":"$paymentReference",""" +
            """"status":"$status","amount":200000,"currency":"NGN"}}"""

    private fun sign(body: String): String =
        HexFormat.of().formatHex(
            Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA512")) }.doFinal(body.toByteArray()),
        )

    private fun webhook(body: String, signature: String? = sign(body)): ResultActionsDsl =
        mockMvc.post("/webhook/paystack") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            signature?.let { header("x-paystack-signature", it) }
        }

    private fun adminOrder(orderId: Long): JsonNode = readJson(
        mockMvc.get("/dev/admin/orders/$orderId") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("00") }.andReturn(),
    )

    private fun refundEmails(reference: String): Int {
        drainOutbox()
        return emailChannel.sentTo(email).count { it.subject == "Your refund for order $reference" }
    }

    @Test
    fun `a signed refund processed webhook settles the refund without a refresh`() {
        val paid = cancelledPaidOrder()
        val refundId = startRefund(paid.orderId)["providerRefundId"].asString()
        paymentGateway.processRefund(paid.paymentReference)

        webhook(event("refund.processed", refundId, paid.paymentReference)).andExpect { status { isOk() } }

        val order = adminOrder(paid.orderId)
        assertEquals("REFUNDED", order["paymentStatus"].asString())
        assertEquals("CANCELLED", order["status"].asString())
        assertEquals("PROCESSED", order["refunds"][0]["status"].asString())
        assertEquals(1, refundEmails(paid.reference))
    }

    @Test
    fun `a repeated webhook and a later refresh change nothing more`() {
        val paid = cancelledPaidOrder()
        val refund = startRefund(paid.orderId)
        paymentGateway.processRefund(paid.paymentReference)
        val body = event("refund.processed", refund["providerRefundId"].asString(), paid.paymentReference)

        webhook(body).andExpect { status { isOk() } }
        webhook(body).andExpect { status { isOk() } }
        mockMvc.post("/dev/admin/refunds/${refund["refundId"].asLong()}/refresh") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("00") }

        val order = adminOrder(paid.orderId)
        assertEquals("REFUNDED", order["paymentStatus"].asString())
        assertEquals(1, order["refunds"].size())
        // One history row for the refund: cancel history (3 rows) + 1.
        assertEquals(4, order["statusHistory"].size())
        assertEquals(1, refundEmails(paid.reference))
    }

    @Test
    fun `a refund failed webhook marks it failed and the order can be refunded again`() {
        val paid = cancelledPaidOrder()
        val refundId = startRefund(paid.orderId)["providerRefundId"].asString()
        paymentGateway.failRefund(paid.paymentReference, reason = "Customer bank account closed")

        webhook(event("refund.failed", refundId, paid.paymentReference, status = "failed")).andExpect { status { isOk() } }

        val order = adminOrder(paid.orderId)
        assertEquals("PAID", order["paymentStatus"].asString())
        assertEquals("FAILED", order["refunds"][0]["status"].asString())
        assertEquals("Customer bank account closed", order["refunds"][0]["failureReason"].asString())
        assertEquals(true, order["canRefund"].asBoolean())
        assertEquals(0, refundEmails(paid.reference))
    }

    @Test
    fun `pending and processing webhooks change nothing`() {
        val paid = cancelledPaidOrder()
        val refundId = startRefund(paid.orderId)["providerRefundId"].asString()
        // Even if Paystack has finished meanwhile: only processed / failed events are acted on.
        paymentGateway.processRefund(paid.paymentReference)

        listOf("refund.pending", "refund.processing").forEach {
            webhook(event(it, refundId, paid.paymentReference, status = "pending")).andExpect { status { isOk() } }
        }

        val order = adminOrder(paid.orderId)
        assertEquals("PAID", order["paymentStatus"].asString())
        assertEquals("PENDING", order["refunds"][0]["status"].asString())
        assertEquals("pending", order["refunds"][0]["providerStatus"].asString())
    }

    @Test
    fun `the body is not trusted, so a processed event Paystack does not confirm leaves the refund pending`() {
        val paid = cancelledPaidOrder()
        val refundId = startRefund(paid.orderId)["providerRefundId"].asString()
        // Paystack itself still says pending.

        webhook(event("refund.processed", refundId, paid.paymentReference)).andExpect { status { isOk() } }

        val order = adminOrder(paid.orderId)
        assertEquals("PAID", order["paymentStatus"].asString())
        assertEquals("PENDING", order["refunds"][0]["status"].asString())
        assertEquals(0, refundEmails(paid.reference))
    }

    @Test
    fun `a refund whose start never heard back is found through its payment and settled`() {
        val paid = cancelledPaidOrder()
        paymentGateway.loseRefundAnswer(paid.paymentReference)
        startRefund(paid.orderId).let { assertTrue(it.get("providerRefundId")?.isNull ?: true) }
        val atPaystack = paymentGateway.findRefunds(null, paid.paymentReference).single().refundId
        paymentGateway.processRefund(paid.paymentReference)

        webhook(event("refund.processed", atPaystack, paid.paymentReference)).andExpect { status { isOk() } }

        val order = adminOrder(paid.orderId)
        assertEquals("REFUNDED", order["paymentStatus"].asString())
        assertEquals(atPaystack, order["refunds"][0]["providerRefundId"].asString())
        assertEquals(1, paymentGateway.refundRequests(paid.paymentReference).size)
        assertEquals(1, refundEmails(paid.reference))
    }

    @Test
    fun `unknown refunds are ignored, bad signatures refused, and an unreachable Paystack is retried`() {
        // Not ours (e.g. a refund made in the Paystack dashboard for another payment): 200, nothing to do.
        webhook(event("refund.processed", "123456789", "PAY-NOT-OURS")).andExpect { status { isOk() } }

        val paid = cancelledPaidOrder()
        val refundId = startRefund(paid.orderId)["providerRefundId"].asString()
        paymentGateway.processRefund(paid.paymentReference)
        val body = event("refund.processed", refundId, paid.paymentReference)

        webhook(body, signature = null).andExpect { status { isUnauthorized() } }
        webhook(body, signature = "0".repeat(128)).andExpect { status { isUnauthorized() } }
        paymentGateway.down = true
        try {
            webhook(body).andExpect { status { isBadGateway() } }
        } finally {
            paymentGateway.down = false
        }
        assertEquals("PAID", adminOrder(paid.orderId)["paymentStatus"].asString())

        // Paystack's retry then settles it.
        webhook(body).andExpect { status { isOk() } }
        assertEquals("REFUNDED", adminOrder(paid.orderId)["paymentStatus"].asString())
        assertEquals(1, refundEmails(paid.reference))
    }
}
