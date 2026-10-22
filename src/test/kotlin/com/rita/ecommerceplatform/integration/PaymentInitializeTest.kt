package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.service.PaymentService
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Starting a payment (checkpoint 1.4, Step 2). Paystack is the fake; each test uses a fresh customer. */
class PaymentInitializeTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private lateinit var token: String
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        token = newCustomerToken()
        categoryId = createCategory()
        createAddress(token)
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createCategory(): Int {
        val result = mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun createProduct(price: Any): Long {
        val result = mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to unique("Phone"),
                "sku" to unique("sku"),
                "categoryId" to categoryId,
                "price" to price,
                "stockQuantity" to 10,
            )
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["productId"].asLong()
    }

    private fun createAddress(withToken: String) {
        mockMvc.post("/api/customer/addresses") {
            header("Authorization", "Bearer $withToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "recipientName" to "Ada Obi",
                "recipientPhoneNumber" to "08012345678",
                "addressLine1" to "12 Allen Avenue",
                "city" to "Ikeja",
                "state" to "Lagos",
                "country" to "Nigeria",
            )
        }.andExpect { status { isCreated() } }
    }

    /** Puts one new product in the cart and checks out; returns the checkout response (the order). */
    private fun placeOrder(price: Any = 1000, withToken: String = token): JsonNode {
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer $withToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to createProduct(price), "quantity" to 1)
        }.andExpect { status { isOk() } }
        val result = mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer $withToken")
            header("Idempotency-Key", UUID.randomUUID().toString())
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)
    }

    private fun initialize(orderReference: String?, withToken: String? = token): ResultActionsDsl =
        mockMvc.post("/api/customer/payments/initialize") {
            withToken?.let { header("Authorization", "Bearer $it") }
            contentType = MediaType.APPLICATION_JSON
            content = json("orderReference" to orderReference)
        }

    // CITEXT columns are cast to text: read raw, the driver returns them as PGobject, not String.
    private fun paymentRows(orderId: Long): List<Map<String, Any?>> =
        jdbcClient.sql(
            """
            SELECT paymentreference::text AS paymentreference, paymentstatus::text AS paymentstatus, paymentamount,
                   paymentcurrency::text AS paymentcurrency, paymentprovider::text AS paymentprovider, paymentauthorizationurl
            FROM public.ec_payment WHERE paymentorderid = :orderId ORDER BY paymentid
            """
        )
            .param("orderId", orderId)
            .query()
            .listOfRows()

    private fun sentToGateway(paymentReference: String) = paymentGateway.initialized.filter { it.reference == paymentReference }

    @Test
    fun `starts a payment for the order's total and returns the payment page`() {
        val order = placeOrder(price = 1000)
        val total = order["total"].decimalValue()

        val result = initialize(order["reference"].asString()).andExpect {
            status { isCreated() }
            jsonPath("$.orderReference") { value(order["reference"].asString()) }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.currency") { value("NGN") }
            jsonPath("$.amount") { value(total.toDouble()) }
        }.andReturn()
        val body = readJson(result)
        val reference = body["paymentReference"].asString()
        assertEquals("https://checkout.example.test/$reference", body["authorizationUrl"].asString())

        // What "Paystack" received: amount in kobo from the order, and the customer's own email.
        val sent = sentToGateway(reference).single()
        assertEquals(total.movePointRight(2).longValueExact(), sent.amountKobo)
        assertEquals("NGN", sent.currency)
        val email = jdbcClient.sql(
            "SELECT c.customeremail::text FROM public.ec_customer c JOIN public.ec_order o ON o.ordercustomerid = c.customerid WHERE o.orderid = :orderId"
        ).param("orderId", order["orderId"].asLong()).query(String::class.java).single()
        assertEquals(email, sent.email)

        val row = paymentRows(order["orderId"].asLong()).single()
        assertEquals(reference, row["paymentreference"])
        assertEquals("PENDING", row["paymentstatus"])
        assertEquals("PAYSTACK", row["paymentprovider"])
        assertEquals(0, total.compareTo(row["paymentamount"] as BigDecimal))
        assertEquals(body["authorizationUrl"].asString(), row["paymentauthorizationurl"])
    }

    @Test
    fun `amounts with kobo are sent exactly`() {
        val order = placeOrder(price = BigDecimal("12500.55"))
        val total = order["total"].decimalValue()

        val result = initialize(order["reference"].asString()).andExpect { status { isCreated() } }.andReturn()

        val sent = sentToGateway(readJson(result)["paymentReference"].asString()).single()
        assertEquals(total.movePointRight(2).longValueExact(), sent.amountKobo)
        assertEquals(55L, sent.amountKobo % 100)
    }

    @Test
    fun `starting again returns the open payment instead of a second one`() {
        val order = placeOrder()
        val first = readJson(initialize(order["reference"].asString()).andExpect { status { isCreated() } }.andReturn())

        val second = readJson(initialize(order["reference"].asString()).andExpect { status { isOk() } }.andReturn())

        assertEquals(first["paymentReference"].asString(), second["paymentReference"].asString())
        assertEquals(first["authorizationUrl"].asString(), second["authorizationUrl"].asString())
        assertEquals(1, sentToGateway(first["paymentReference"].asString()).size)
        assertEquals(1, paymentRows(order["orderId"].asLong()).size)
    }

    @Test
    fun `the order reference is matched case-insensitively`() {
        val order = placeOrder()
        initialize(order["reference"].asString().lowercase()).andExpect {
            status { isCreated() }
            jsonPath("$.orderReference") { value(order["reference"].asString()) }
        }
    }

    @Test
    fun `another customer's order or an unknown reference is not found`() {
        val order = placeOrder()
        val otherToken = newCustomerToken()

        initialize(order["reference"].asString(), withToken = otherToken).andExpect {
            status { isNotFound() }
            jsonPath("$.message") { value("Order not found") }
        }
        initialize("ORD-DOES-NOT-EXIST").andExpect { status { isNotFound() } }
        assertEquals(0, paymentRows(order["orderId"].asLong()).size)
    }

    @Test
    fun `an order that is not pending or already paid cannot be paid`() {
        val cancelled = placeOrder()
        jdbcClient.sql("UPDATE public.ec_order SET orderstatus = 'CANCELLED' WHERE orderid = :id")
            .param("id", cancelled["orderId"].asLong()).update()
        val paid = placeOrder()
        jdbcClient.sql("UPDATE public.ec_order SET orderpaymentstatus = 'PAID' WHERE orderid = :id")
            .param("id", paid["orderId"].asLong()).update()

        listOf(cancelled, paid).forEach { order ->
            initialize(order["reference"].asString()).andExpect {
                status { isConflict() }
                jsonPath("$.message") { value("This order cannot be paid") }
            }
            assertEquals(0, paymentRows(order["orderId"].asLong()).size)
        }
    }

    @Test
    fun `when Paystack is down the attempt fails with 502 and a later start works`() {
        val order = placeOrder()
        paymentGateway.down = true
        try {
            initialize(order["reference"].asString()).andExpect { status { isBadGateway() } }
        } finally {
            paymentGateway.down = false
        }
        assertEquals(listOf("FAILED"), paymentRows(order["orderId"].asLong()).map { it["paymentstatus"] })

        initialize(order["reference"].asString()).andExpect { status { isCreated() } }

        val rows = paymentRows(order["orderId"].asLong())
        assertEquals(listOf("FAILED", "PENDING"), rows.map { it["paymentstatus"] })
        assertNotEquals(rows[0]["paymentreference"], rows[1]["paymentreference"])
    }

    @Test
    fun `an attempt still being started blocks a second start until it is stale`() {
        val order = placeOrder()
        val orderId = order["orderId"].asLong()
        // An attempt with no page yet, as if another request were waiting for Paystack.
        jdbcClient.sql(
            """
            INSERT INTO public.ec_payment (paymentorderid, paymentreference, paymentprovider, paymentamount)
            VALUES (:orderId, :reference, 'PAYSTACK', :amount)
            """
        )
            .param("orderId", orderId)
            .param("reference", PaymentService.newReference())
            .param("amount", order["total"].decimalValue())
            .update()

        initialize(order["reference"].asString()).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("A payment for this order is already being started, try again in a moment") }
        }

        // Older than the stale limit: the request that started it must have crashed.
        jdbcClient.sql(
            "UPDATE public.ec_payment SET paymentcreatedat = paymentcreatedat - make_interval(secs => :secs) WHERE paymentorderid = :orderId"
        )
            .param("secs", PaymentService.STALE_START_SECONDS + 1)
            .param("orderId", orderId)
            .update()

        initialize(order["reference"].asString()).andExpect { status { isCreated() } }
        assertEquals(listOf("FAILED", "PENDING"), paymentRows(orderId).map { it["paymentstatus"] })
    }

    @Test
    fun `a missing or blank order reference is rejected`() {
        initialize(null).andExpect { status { isBadRequest() } }
        initialize("   ").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `only a logged-in customer can start a payment`() {
        val order = placeOrder()
        initialize(order["reference"].asString(), withToken = null).andExpect { status { isUnauthorized() } }
        initialize(order["reference"].asString(), withToken = adminToken).andExpect { status { isForbidden() } }
        assertEquals(0, paymentRows(order["orderId"].asLong()).size)
    }

    @Test
    fun `kobo conversion is exact and refuses fractions of a kobo`() {
        assertEquals(1250050L, PaymentService.toKobo(BigDecimal("12500.50")))
        assertEquals(100L, PaymentService.toKobo(BigDecimal("1")))
        assertFailsWith<ArithmeticException> { PaymentService.toKobo(BigDecimal("1.005")) }
    }
}
