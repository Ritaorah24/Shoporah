package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Admin order list, detail and fulfilment status changes (checkpoint 1.4, Step 4). Paystack is the fake. */
class AdminOrderTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private var categoryId: Int = 0

    private data class Shopper(val email: String, val token: String)

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        categoryId = createCategory()
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun newShopper(): Shopper {
        val email = uniqueEmail()
        registerCustomer(email)
        val shopper = Shopper(email, loginCustomer(email))
        mockMvc.post("/api/customer/addresses") {
            header("Authorization", "Bearer ${shopper.token}")
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
        return shopper
    }

    private fun createCategory(): Int {
        val result = mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    /** A PENDING, UNPAID order of 2 units of a new product. Returns the orderId. */
    private fun placeOrder(shopper: Shopper): Long {
        val product = mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Phone"), "sku" to unique("sku"), "categoryId" to categoryId, "price" to 1000, "stockQuantity" to 10)
        }.andExpect { responseCode("00") }.andReturn()
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer ${shopper.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to readJson(product)["productId"].asLong(), "quantity" to 2)
        }.andExpect { responseCode("00") }
        val order = mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer ${shopper.token}")
            header("Idempotency-Key", UUID.randomUUID().toString())
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(order)["orderId"].asLong()
    }

    private fun orderReference(orderId: Long): String =
        jdbcClient.sql("SELECT orderreference::text FROM public.ec_order WHERE orderid = :id")
            .param("id", orderId).query(String::class.java).single()

    /** Starts a payment, lets "Paystack" answer with `outcome`, and verifies it. Returns the payment reference. */
    private fun pay(shopper: Shopper, orderId: Long, outcome: (String) -> Unit = paymentGateway::pay): String {
        val result = mockMvc.post("/api/customer/payments/initialize") {
            header("Authorization", "Bearer ${shopper.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json("orderReference" to orderReference(orderId))
        }.andExpect { responseCode("00") }.andReturn()
        val reference = readJson(result)["paymentReference"].asString()
        outcome(reference)
        mockMvc.get("/api/customer/payments/verify/$reference") { header("Authorization", "Bearer ${shopper.token}") }
            .andExpect { responseCode("00") }
        return reference
    }

    /** A CONFIRMED, PAID order: the starting point of fulfilment. */
    private fun confirmedOrder(shopper: Shopper = newShopper()): Long = placeOrder(shopper).also { pay(shopper, it) }

    private fun move(orderId: Long, status: String?, note: String? = null, token: String? = adminToken): ResultActionsDsl =
        mockMvc.patch("/api/admin/orders/$orderId/status") {
            token?.let { header("Authorization", "Bearer $it") }
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to status, "note" to note)
        }

    private fun detail(orderId: Long): JsonNode =
        readJson(mockMvc.get("/api/admin/orders/$orderId") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("00") }.andReturn())

    private fun list(query: String): JsonNode =
        readJson(mockMvc.get("/api/admin/orders?$query") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("00") }.andReturn())

    private fun status(orderId: Long): String =
        jdbcClient.sql("SELECT orderstatus::text FROM public.ec_order WHERE orderid = :id")
            .param("id", orderId).query(String::class.java).single()

    private fun historyCount(orderId: Long): Int =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_order_status_history WHERE orderstatushistoryorderid = :id")
            .param("id", orderId).query(Int::class.javaObjectType).single()

    // By index: in Jackson 3, JsonNode has a map method of its own, so Kotlin's collection map does not apply.
    private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    private fun JsonNode.texts(): List<String> = items().map { it.asString() }

    @Test
    fun `an admin moves a paid order through processing, shipped and delivered`() {
        val orderId = confirmedOrder()
        assertEquals(listOf("PROCESSING"), detail(orderId)["nextStatuses"].texts())

        move(orderId, "processing").andExpect {
            responseCode("00")
            jsonPath("$.data.status") { value("PROCESSING") }
            jsonPath("$.data.nextStatuses[0]") { value("SHIPPED") }
        }
        move(orderId, "SHIPPED", note = "  GIG Logistics, tracking 12345  ").andExpect {
            responseCode("00")
            jsonPath("$.data.status") { value("SHIPPED") }
            jsonPath("$.data.nextStatuses[0]") { value("DELIVERED") }
        }
        move(orderId, "DELIVERED", note = "  ").andExpect {
            responseCode("00")
            jsonPath("$.data.status") { value("DELIVERED") }
            jsonPath("$.data.paymentStatus") { value("PAID") }
            // DELIVERED â†’ REFUNDED exists in the rules, but refunds come in V2.
            jsonPath("$.data.nextStatuses.length()") { value(0) }
        }

        val history = detail(orderId)["statusHistory"]
        assertEquals(listOf("PENDING", "CONFIRMED", "PROCESSING", "SHIPPED", "DELIVERED"), history.items().map { it["status"].asString() })
        assertEquals(listOf("CUSTOMER", "SYSTEM", "ADMIN", "ADMIN", "ADMIN"), history.items().map { it["changedBy"].asString() })
        assertEquals(seedAdminId(), history[2]["adminId"].asInt())
        assertTrue(history[1]["adminId"].isNull)
        assertEquals("SHIPPED", history[4]["fromStatus"].asString())
        assertEquals("GIG Logistics, tracking 12345", history[3]["note"].asString())
        assertTrue(history[4]["note"].isNull)
    }

    @Test
    fun `skipped, backward, repeated and unpaid moves are rejected`() {
        val orderId = confirmedOrder()
        move(orderId, "SHIPPED").andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Order status cannot change from CONFIRMED to SHIPPED") }
        }
        move(orderId, "DELIVERED").andExpect { responseCode("106") }
        move(orderId, "PROCESSING").andExpect { responseCode("00") }
        move(orderId, "PROCESSING").andExpect { responseCode("106") }
        move(orderId, "SHIPPED").andExpect { responseCode("00") }
        move(orderId, "PROCESSING").andExpect { responseCode("106") }
        move(orderId, "DELIVERED").andExpect { responseCode("00") }
        move(orderId, "SHIPPED").andExpect { responseCode("106") }
        assertEquals("DELIVERED", status(orderId))
        assertEquals(5, historyCount(orderId))

        // Not paid yet: only a verified payment can confirm it, so it cannot be processed.
        val unpaid = placeOrder(newShopper())
        move(unpaid, "PROCESSING").andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Order status cannot change from PENDING to PROCESSING") }
        }
        assertEquals("PENDING", status(unpaid))
        assertEquals(0, detail(unpaid)["nextStatuses"].size())

        val cancelled = placeOrder(newShopper())
        jdbcClient.sql("UPDATE public.ec_order SET orderstatus = 'CANCELLED' WHERE orderid = :id").param("id", cancelled).update()
        move(cancelled, "PROCESSING").andExpect { responseCode("106") }
        assertEquals(1, historyCount(cancelled))
    }

    @Test
    fun `statuses other than the three fulfilment steps are a 400`() {
        val orderId = confirmedOrder()
        listOf("CANCELLED", "CONFIRMED", "REFUNDED", "PENDING", "flying", "", null).forEach { value ->
            move(orderId, value).andExpect {
                responseCode("106")
                jsonPath("$.data.fieldErrors[*].field") { value(hasItem("status")) }
            }
        }
        move(orderId, "PROCESSING", note = "x".repeat(501)).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].field") { value("note") }
        }
        assertEquals("CONFIRMED", status(orderId))
        assertEquals(2, historyCount(orderId))
    }

    @Test
    fun `two admins moving the same order at once change it once`() {
        val orderId = confirmedOrder()

        val pool = Executors.newFixedThreadPool(2)
        val statuses = try {
            pool.invokeAll(List(2) { Callable { move(orderId, "PROCESSING").andReturn().responseCodeOf() } }).map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(listOf("00", "106"), statuses.sorted())
        assertEquals("PROCESSING", status(orderId))
        assertEquals(3, historyCount(orderId))
    }

    @Test
    fun `the list shows every customer's orders newest first, with paging and a status filter`() {
        val ada = newShopper()
        val ben = newShopper()
        val older = confirmedOrder(ada)
        val newer = placeOrder(ben)

        val firstPage = list("page=0&size=2")
        assertEquals(listOf(newer, older), firstPage["content"].items().map { it["orderId"].asLong() })
        assertEquals(2, firstPage["size"].asInt())
        val top = firstPage["content"][0]
        assertEquals(ben.email, top["customerEmail"].asString())
        assertEquals("Test User", top["customerName"].asString())
        assertEquals("PENDING", top["status"].asString())
        assertEquals(2, top["itemCount"].asInt())
        assertEquals(ada.email, firstPage["content"][1]["customerEmail"].asString())

        val total = jdbcClient.sql("SELECT COUNT(*) FROM public.ec_order").query(Long::class.javaObjectType).single()
        assertEquals(total, firstPage["totalElements"].asLong())
        assertEquals(50, list("size=1000")["size"].asInt())

        val confirmed = list("status=confirmed&size=50")
        assertTrue(confirmed["content"].items().all { it["status"].asString() == "CONFIRMED" })
        assertEquals(older, confirmed["content"][0]["orderId"].asLong())
        val confirmedCount = jdbcClient.sql("SELECT COUNT(*) FROM public.ec_order WHERE orderstatus = 'CONFIRMED'")
            .query(Long::class.javaObjectType).single()
        assertEquals(confirmedCount, confirmed["totalElements"].asLong())

        mockMvc.get("/api/admin/orders?status=LOST") { header("Authorization", "Bearer $adminToken") }.andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("status must be one of PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED, REFUNDED") }
        }
    }

    @Test
    fun `the detail shows the customer, items, address, every payment attempt and who changed the status`() {
        val shopper = newShopper()
        val orderId = placeOrder(shopper)
        val failed = pay(shopper, orderId, paymentGateway::fail)
        val paid = pay(shopper, orderId)

        val order = detail(orderId)
        assertEquals(orderReference(orderId), order["reference"].asString())
        assertEquals(shopper.email, order["customerEmail"].asString())
        assertEquals("Test User", order["customerName"].asString())
        assertEquals("CONFIRMED", order["status"].asString())
        assertEquals("PAID", order["paymentStatus"].asString())
        assertTrue(!order["paidAt"].isNull)
        assertEquals(2, order["items"][0]["quantity"].asInt())
        assertEquals(0, order["subtotal"].decimalValue().compareTo("2000".toBigDecimal()))
        assertEquals("Ikeja", order["deliveryAddress"]["city"].asString())

        val payments = order["payments"]
        assertEquals(listOf(failed, paid), payments.items().map { it["reference"].asString() })
        assertEquals(listOf("FAILED", "SUCCESS"), payments.items().map { it["status"].asString() })
        assertEquals("NGN", payments[1]["currency"].asString())
        assertEquals("card", payments[1]["channel"].asString())

        assertEquals(listOf("CUSTOMER", "SYSTEM"), order["statusHistory"].items().map { it["changedBy"].asString() })
        assertEquals("Payment verified", order["statusHistory"][1]["note"].asString())
    }

    @Test
    fun `an unknown order is a 404`() {
        mockMvc.get("/api/admin/orders/999999999") { header("Authorization", "Bearer $adminToken") }.andExpect {
            responseCode("25")
            jsonPath("$.responseMessage") { value("Order not found") }
        }
        move(999_999_999, "PROCESSING").andExpect { responseCode("25") }
    }

    @Test
    fun `only admins who changed their password can use the admin order endpoints`() {
        val shopper = newShopper()
        val orderId = confirmedOrder(shopper)

        mockMvc.get("/api/admin/orders").andExpect { responseCode("115") }
        mockMvc.get("/api/admin/orders") { header("Authorization", "Bearer ${shopper.token}") }.andExpect { responseCode("107") }
        move(orderId, "PROCESSING", token = null).andExpect { responseCode("115") }
        move(orderId, "PROCESSING", token = shopper.token).andExpect { responseCode("107") }

        val mustChangeToken = loginSeedAdmin()
        mockMvc.get("/api/admin/orders/$orderId") { header("Authorization", "Bearer $mustChangeToken") }.andExpect { responseCode("107") }
        move(orderId, "PROCESSING", token = mustChangeToken).andExpect { responseCode("107") }
        assertEquals("CONFIRMED", status(orderId))
    }

    @Test
    fun `the customer sees the admin's changes and notes, but not who made them`() {
        val shopper = newShopper()
        val orderId = confirmedOrder(shopper)
        move(orderId, "PROCESSING").andExpect { responseCode("00") }
        move(orderId, "SHIPPED", note = "GIG Logistics, tracking 12345").andExpect { responseCode("00") }

        mockMvc.get("/api/customer/orders/$orderId") { header("Authorization", "Bearer ${shopper.token}") }.andExpect {
            responseCode("00")
            jsonPath("$.data.status") { value("SHIPPED") }
            jsonPath("$.data.statusHistory[3].status") { value("SHIPPED") }
            jsonPath("$.data.statusHistory[3].note") { value("GIG Logistics, tracking 12345") }
            jsonPath("$.data.statusHistory[3].changedBy") { doesNotExist() }
            jsonPath("$.data.statusHistory[3].adminId") { doesNotExist() }
        }
    }
}
