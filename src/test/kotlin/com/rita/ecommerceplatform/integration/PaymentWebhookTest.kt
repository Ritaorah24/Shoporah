package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.service.PaymentService
import com.rita.ecommerceplatform.service.PaymentSettlement
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals

/**
 * The Paystack webhook (checkpoint 1.4, Step 5). Bodies are signed here with the configured secret key,
 * as Paystack signs them; what Paystack says about the payment comes from the fake, as in PaymentVerifyTest.
 */
class PaymentWebhookTest : IntegrationTestBase() {

    @Value("\${app.paystack.secret-key}")
    private lateinit var secretKey: String

    private lateinit var adminToken: String
    private lateinit var token: String
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        token = newCustomerToken()
        categoryId = createCategory()
        createAddress()
    }

    private data class Placed(val order: JsonNode, val productIds: List<Long>) {
        val orderId get() = order["orderId"].asLong()
        val reference: String get() = order["reference"].asString()
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createCategory(): Int {
        val result = mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun createProduct(stock: Int): Long {
        val result = mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to unique("Phone"),
                "sku" to unique("sku"),
                "categoryId" to categoryId,
                "price" to 1000,
                "stockQuantity" to stock,
            )
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["productId"].asLong()
    }

    private fun createAddress() {
        mockMvc.post("/api/customer/addresses") {
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

    /** One new product (stock 10), quantity 2 in the cart, then checkout. */
    private fun placeOrder(): Placed {
        val productId = createProduct(stock = 10)
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to productId, "quantity" to 2)
        }.andExpect { responseCode("00") }
        val result = mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", UUID.randomUUID().toString())
        }.andExpect { responseCode("00") }.andReturn()
        return Placed(readJson(result), listOf(productId))
    }

    /** Starts a payment for the order and returns its reference. */
    private fun initialize(placed: Placed): String {
        val result = mockMvc.post("/api/customer/payments/initialize") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("orderReference" to placed.reference)
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["paymentReference"].asString()
    }

    /** A body shaped like Paystack's; the status and amount in it are never trusted by the app. */
    private fun event(reference: String, event: String = "charge.success") =
        """{"event":"$event","data":{"id":1,"reference":"$reference","status":"success","amount":200000,"currency":"NGN"}}"""

    private fun sign(body: String, key: String = secretKey): String =
        HexFormat.of().formatHex(
            Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA512")) }.doFinal(body.toByteArray())
        )

    /** Sends the body to the webhook, signed correctly unless another signature (or none) is given. */
    private fun webhook(body: String, signature: String? = sign(body), bearer: String? = null): ResultActionsDsl =
        mockMvc.post("/api/webhooks/paystack") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            signature?.let { header("x-paystack-signature", it) }
            bearer?.let { header("Authorization", "Bearer $it") }
        }

    private fun verify(paymentReference: String): ResultActionsDsl =
        mockMvc.get("/api/customer/payments/verify/$paymentReference") { header("Authorization", "Bearer $token") }

    private fun stock(productId: Long): Int =
        jdbcClient.sql("SELECT productstockquantity FROM public.ec_product WHERE productid = :id")
            .param("id", productId).query(Int::class.javaObjectType).single()

    // CITEXT columns are cast to text: read raw, the driver returns them as PGobject, not String.
    private fun orderState(orderId: Long): Map<String, Any?> =
        jdbcClient.sql("SELECT orderstatus::text AS status, orderpaymentstatus::text AS paymentstatus FROM public.ec_order WHERE orderid = :id")
            .param("id", orderId).query().singleRow()

    private fun paymentState(reference: String): Map<String, Any?> =
        jdbcClient.sql("SELECT paymentstatus::text AS status, paymentgatewayresponse FROM public.ec_payment WHERE paymentreference = :reference")
            .param("reference", reference).query().singleRow()

    private fun saleMovements(orderId: Long): Int =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_inventory_movement WHERE inventorymovementorderid = :id AND inventorymovementtype = 'SALE'")
            .param("id", orderId).query(Int::class.javaObjectType).single()

    private fun lastHistoryNote(orderId: Long): String? =
        jdbcClient.sql(
            """
            SELECT orderstatushistorynote FROM public.ec_order_status_history WHERE orderstatushistoryorderid = :id
            ORDER BY orderstatushistorycreatedat DESC, orderstatushistoryid DESC LIMIT 1
            """
        ).param("id", orderId).query(String::class.java).single()

    private fun assertConfirmedOnce(placed: Placed) {
        assertEquals("CONFIRMED", orderState(placed.orderId)["status"])
        assertEquals("PAID", orderState(placed.orderId)["paymentstatus"])
        assertEquals(8, stock(placed.productIds[0]))
        assertEquals(1, saleMovements(placed.orderId))
    }

    private fun assertUntouched(placed: Placed) {
        assertEquals("PENDING", orderState(placed.orderId)["status"])
        assertEquals("UNPAID", orderState(placed.orderId)["paymentstatus"])
        assertEquals(10, stock(placed.productIds[0]))
        assertEquals(0, saleMovements(placed.orderId))
    }

    @Test
    fun `a signed charge success confirms the order without the customer ever verifying`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)

        // No token: this is Paystack calling, after the customer closed the browser.
        webhook(event(reference)).andExpect { status { isOk() } }

        assertConfirmedOnce(placed)
        assertEquals("SUCCESS", paymentState(reference)["status"])
        assertEquals("Payment verified", lastHistoryNote(placed.orderId))
        assertEquals(1, paymentGateway.verifyCalls(reference))
    }

    @Test
    fun `a missing or wrong signature is rejected and changes nothing`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)
        val body = event(reference)

        webhook(body, signature = null).andExpect {
            status { isUnauthorized() }
            jsonPath("$.responseMessage") { value("Invalid webhook signature") }
        }
        webhook(body, signature = "0".repeat(128)).andExpect { status { isUnauthorized() } }
        webhook(body, signature = sign(body, key = "sk_test_someone_else")).andExpect { status { isUnauthorized() } }
        // Signed, then changed on the way: the signature no longer matches.
        webhook(body.replace("200000", "100"), signature = sign(body)).andExpect { status { isUnauthorized() } }

        assertUntouched(placed)
        assertEquals("PENDING", paymentState(reference)["status"])
        assertEquals(0, paymentGateway.verifyCalls(reference))
    }

    @Test
    fun `the same webhook sent again settles the payment once`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)

        repeat(3) { webhook(event(reference)).andExpect { status { isOk() } } }

        assertConfirmedOnce(placed)
        // Once settled, a repeat does not even ask Paystack again.
        assertEquals(1, paymentGateway.verifyCalls(reference))
    }

    @Test
    fun `a webhook and a verify in either order settle the payment once`() {
        val webhookFirst = placeOrder()
        val first = initialize(webhookFirst)
        paymentGateway.pay(first)
        webhook(event(first)).andExpect { status { isOk() } }
        verify(first).andExpect {
            responseCode("00")
            jsonPath("$.data.paymentStatus") { value("SUCCESS") }
            jsonPath("$.data.orderStatus") { value("CONFIRMED") }
        }
        assertConfirmedOnce(webhookFirst)

        val verifyFirst = placeOrder()
        val second = initialize(verifyFirst)
        paymentGateway.pay(second)
        verify(second).andExpect { responseCode("00"); jsonPath("$.responseMessage") { value("Successful") } }
        webhook(event(second)).andExpect { status { isOk() } }
        assertConfirmedOnce(verifyFirst)
    }

    @Test
    fun `a webhook and a verify at the same moment take the stock once`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)

        val pool = Executors.newFixedThreadPool(2)
        val statuses = try {
            pool.invokeAll(
                listOf(
                    Callable { webhook(event(reference)).andReturn().response.status == 200 },
                    Callable { verify(reference).andReturn().responseCodeOf() == "00" },
                )
            ).map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(listOf(true, true), statuses)
        assertConfirmedOnce(placed)
    }

    @Test
    fun `a signed body claiming success is not trusted when Paystack says otherwise`() {
        // Paystack has not seen the customer pay yet: the payment stays PENDING.
        val notPaid = placeOrder()
        val notPaidReference = initialize(notPaid)
        webhook(event(notPaidReference)).andExpect { status { isOk() } }
        assertEquals("PENDING", paymentState(notPaidReference)["status"])
        assertUntouched(notPaid)

        val failed = placeOrder()
        val failedReference = initialize(failed)
        paymentGateway.fail(failedReference)
        webhook(event(failedReference)).andExpect { status { isOk() } }
        assertEquals("FAILED", paymentState(failedReference)["status"])
        assertUntouched(failed)

        // The amount check of verify applies too (the body's amount is ignored; Paystack's is checked).
        val cheap = placeOrder()
        val cheapReference = initialize(cheap)
        paymentGateway.pay(cheapReference, amountKobo = 100)
        webhook(event(cheapReference)).andExpect { status { isOk() } }
        assertEquals(PaymentSettlement.MISMATCH, paymentState(cheapReference)["paymentgatewayresponse"])
        assertUntouched(cheap)
    }

    @Test
    fun `other events, unknown references and tokens are handled without changing anything`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)

        webhook(event(reference, event = "transfer.success")).andExpect { status { isOk() } }
        webhook(event(PaymentService.newReference())).andExpect { status { isOk() } }
        webhook("""{"event":"charge.success","data":{}}""").andExpect { status { isOk() } }
        assertUntouched(placed)
        assertEquals(0, paymentGateway.verifyCalls(reference))

        // Signed but not JSON: Paystack would not send it, so it is a 400 (and logged).
        webhook("not json").andExpect { status { isBadRequest() } }

        // A token is neither needed nor a way in: only the signature counts.
        webhook(event(reference), signature = null, bearer = adminToken).andExpect { status { isUnauthorized() } }
        webhook(event(reference), bearer = token).andExpect { status { isOk() } }
        assertConfirmedOnce(placed)
    }

    @Test
    fun `when Paystack cannot be asked the webhook fails so that Paystack retries it`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)

        paymentGateway.down = true
        try {
            webhook(event(reference)).andExpect { status { isBadGateway() } }
        } finally {
            paymentGateway.down = false
        }
        assertEquals("PENDING", paymentState(reference)["status"])
        assertUntouched(placed)

        // Paystack's retry, once we can reach it again.
        webhook(event(reference)).andExpect { status { isOk() } }
        assertConfirmedOnce(placed)
    }
}
