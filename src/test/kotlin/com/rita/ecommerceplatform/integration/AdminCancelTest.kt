package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.service.OrderCancellation
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** An admin cancelling an order that has not shipped, with stock restore (checkpoint 2.2, Step 4). Paystack is the fake. */
class AdminCancelTest : IntegrationTestBase() {

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

    private data class Placed(val orderId: Long, val reference: String, val productIds: List<Long>)

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createCategory(): Int {
        val result = mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun createProduct(stock: Int = 10): Long {
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

    /** Each product, `quantity` of it in the cart, then checkout. */
    private fun placeOrder(productIds: List<Long> = listOf(createProduct(), createProduct()), quantity: Int = 2): Placed {
        productIds.forEach { productId ->
            mockMvc.post("/api/customer/cart/items") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = json("productId" to productId, "quantity" to quantity)
            }.andExpect { responseCode("00") }
        }
        val result = mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", UUID.randomUUID().toString())
        }.andExpect { responseCode("00") }.andReturn()
        val order = readJson(result)
        return Placed(order["orderId"].asLong(), order["reference"].asString(), productIds)
    }

    private fun initialize(placed: Placed): String {
        val result = mockMvc.post("/api/customer/payments/initialize") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("orderReference" to placed.reference)
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["paymentReference"].asString()
    }

    private fun payAndVerify(placed: Placed) {
        val reference = initialize(placed)
        paymentGateway.pay(reference)
        mockMvc.get("/api/customer/payments/verify/$reference") { header("Authorization", "Bearer $token") }
            .andExpect { responseCode("00"); jsonPath("$.responseMessage") { value("Successful") } }
    }

    /** A paid order: 2 of each of two products (stock 10 → 8). */
    private fun paidOrder(): Placed = placeOrder().also(::payAndVerify)

    private fun moveTo(orderId: Long, status: String) {
        mockMvc.patch("/api/admin/orders/$orderId/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to status)
        }.andExpect { responseCode("00") }
    }

    private fun cancel(orderId: Long, reason: String? = "Customer called to cancel", withToken: String? = adminToken): ResultActionsDsl =
        mockMvc.post("/api/admin/orders/$orderId/cancel") {
            withToken?.let { header("Authorization", "Bearer $it") }
            contentType = MediaType.APPLICATION_JSON
            content = json("reason" to reason)
        }

    private fun stock(productId: Long): Int =
        jdbcClient.sql("SELECT productstockquantity FROM public.ec_product WHERE productid = :id")
            .param("id", productId).query(Int::class.javaObjectType).single()

    private fun orderState(orderId: Long): Pair<String, String> =
        jdbcClient.sql("SELECT orderstatus::text AS s, orderpaymentstatus::text AS p FROM public.ec_order WHERE orderid = :id")
            .param("id", orderId).query { rs, _ -> rs.getString("s") to rs.getString("p") }.single()

    /** (productId, quantity change, stock after, adminId) of the order's ORDER_CANCELLED movements. */
    private fun restores(orderId: Long): List<List<Any?>> =
        jdbcClient.sql(
            """
            SELECT inventorymovementproductid, inventorymovementquantitychange, inventorymovementstockafter, inventorymovementadminid
            FROM public.ec_inventory_movement
            WHERE inventorymovementorderid = :id AND inventorymovementtype = 'ORDER_CANCELLED'
            ORDER BY inventorymovementproductid
            """
        ).param("id", orderId).query { rs, _ ->
            listOf(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getObject(4, Int::class.javaObjectType))
        }.list()

    private fun cancelEmails(reference: String): List<String> {
        drainOutbox()
        return emailChannel.sentTo(email).filter { it.subject == "Your order $reference was cancelled" }.map { it.body!! }
    }

    private fun canCancel(orderId: Long): Boolean {
        val result = mockMvc.get("/api/admin/orders/$orderId") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("00") }.andReturn()
        return readJson(result)["canCancel"].asBoolean()
    }

    @Test
    fun `a confirmed order is cancelled, its stock goes back and a refund is flagged`() {
        val placed = paidOrder()
        placed.productIds.forEach { assertEquals(8, stock(it)) }

        cancel(placed.orderId, reason = "  Item damaged in the warehouse  ").andExpect {
            responseCode("00")
            jsonPath("$.data.status") { value("CANCELLED") }
            jsonPath("$.data.paymentStatus") { value("PAID") }
            jsonPath("$.data.canCancel") { value(false) }
            jsonPath("$.data.statusHistory[2].status") { value("CANCELLED") }
            jsonPath("$.data.statusHistory[2].changedBy") { value("ADMIN") }
            jsonPath("$.data.statusHistory[2].adminId") { value(seedAdminId()) }
            jsonPath("$.data.statusHistory[2].note") {
                value("${OrderCancellation.CANCELLED_BY_ADMIN}: Item damaged in the warehouse. ${OrderCancellation.REFUND_NEEDED}")
            }
        }

        placed.productIds.forEach { assertEquals(10, stock(it)) }
        assertEquals(
            placed.productIds.sorted().map { listOf(it, 2, 10, seedAdminId()) },
            restores(placed.orderId),
        )
        val body = cancelEmails(placed.reference).single()
        assertTrue(body.contains("Reason: Item damaged in the warehouse"))
        assertTrue(body.contains("Your payment of ₦4,000.00 will be refunded."), body)
    }

    @Test
    fun `a processing order can be cancelled too`() {
        val placed = paidOrder()
        moveTo(placed.orderId, "PROCESSING")
        assertTrue(canCancel(placed.orderId))

        cancel(placed.orderId).andExpect { responseCode("00") }

        assertEquals("CANCELLED" to "PAID", orderState(placed.orderId))
        placed.productIds.forEach { assertEquals(10, stock(it)) }
        assertEquals(1, cancelEmails(placed.reference).size)
    }

    @Test
    fun `an unpaid order is cancelled without touching stock, and its open payment is closed`() {
        val placed = placeOrder()
        val paymentReference = initialize(placed)
        assertTrue(canCancel(placed.orderId))

        cancel(placed.orderId).andExpect {
            responseCode("00")
            jsonPath("$.data.paymentStatus") { value("UNPAID") }
            jsonPath("$.data.statusHistory[1].note") { value("${OrderCancellation.CANCELLED_BY_ADMIN}: Customer called to cancel") }
            jsonPath("$.data.payments[0].status") { value("FAILED") }
            jsonPath("$.data.payments[0].gatewayResponse") { value(OrderCancellation.PAYMENT_CLOSED_BY_ADMIN) }
        }

        assertEquals(1, paymentGateway.verifyCalls(paymentReference))
        placed.productIds.forEach { assertEquals(10, stock(it)) }
        assertEquals(emptyList(), restores(placed.orderId))
        assertTrue(cancelEmails(placed.reference).single().contains("You were not charged."))
    }

    @Test
    fun `an unpaid order that was just paid is not cancelled, so the admin can look again`() {
        val placed = placeOrder()
        paymentGateway.pay(initialize(placed))

        cancel(placed.orderId).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("This order was just paid; check it and cancel again if needed") }
        }
        assertEquals("CONFIRMED" to "PAID", orderState(placed.orderId))
        placed.productIds.forEach { assertEquals(8, stock(it)) }

        // Cancelling again, now knowingly, restores the stock and flags the refund.
        cancel(placed.orderId).andExpect { responseCode("00") }
        placed.productIds.forEach { assertEquals(10, stock(it)) }
        assertTrue(cancelEmails(placed.reference).single().contains("will be refunded"))
    }

    @Test
    fun `shipped and delivered orders cannot be cancelled`() {
        val placed = paidOrder()
        moveTo(placed.orderId, "PROCESSING")
        moveTo(placed.orderId, "SHIPPED")
        assertEquals(false, canCancel(placed.orderId))

        cancel(placed.orderId).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Shipped or delivered orders cannot be cancelled; use a refund instead") }
        }
        moveTo(placed.orderId, "DELIVERED")
        cancel(placed.orderId).andExpect { responseCode("106") }

        assertEquals("DELIVERED" to "PAID", orderState(placed.orderId))
        placed.productIds.forEach { assertEquals(8, stock(it)) }
        assertEquals(emptyList(), restores(placed.orderId))
    }

    @Test
    fun `cancelling twice restores the stock once and sends one email`() {
        val placed = paidOrder()

        cancel(placed.orderId).andExpect { responseCode("00") }
        cancel(placed.orderId).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Order status cannot change from CANCELLED to CANCELLED") }
        }

        placed.productIds.forEach { assertEquals(10, stock(it)) }
        assertEquals(2, restores(placed.orderId).size)
        assertEquals(1, cancelEmails(placed.reference).size)
    }

    @Test
    fun `two admins cancelling at the same moment restore the stock exactly once`() {
        val placed = paidOrder()

        val pool = Executors.newFixedThreadPool(2)
        val statuses = try {
            pool.invokeAll(List(2) { Callable { cancel(placed.orderId).andReturn().responseCodeOf() } }).map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(listOf("00", "106"), statuses.sorted())
        placed.productIds.forEach { assertEquals(10, stock(it)) }
        assertEquals(2, restores(placed.orderId).size)
    }

    @Test
    fun `the database rejects a second restore for the same order and product`() {
        val placed = paidOrder()
        cancel(placed.orderId).andExpect { responseCode("00") }

        assertFailsWith<DuplicateKeyException> {
            jdbcClient.sql(
                """
                INSERT INTO public.ec_inventory_movement (
                    inventorymovementproductid, inventorymovementtype, inventorymovementquantitychange,
                    inventorymovementstockafter, inventorymovementorderid
                )
                VALUES (:productId, 'ORDER_CANCELLED', 2, 12, :orderId)
                """
            ).param("productId", placed.productIds.first()).param("orderId", placed.orderId).update()
        }
    }

    @Test
    fun `an order cancelled because stock ran out after payment tells the customer`() {
        // Two unpaid orders for the last 2 units: the first payment takes them, the second finds none.
        val productId = createProduct(stock = 2)
        val first = placeOrder(listOf(productId))
        val second = placeOrder(listOf(productId))
        payAndVerify(first)
        payAndVerify(second)

        assertEquals("CANCELLED" to "PAID", orderState(second.orderId))
        val body = cancelEmails(second.reference).single()
        assertTrue(body.contains("Sold out before your payment was confirmed"), body)
        assertTrue(body.contains("will be refunded"), body)
        // Nothing was taken for it, so an admin action can never put anything back for it.
        assertEquals(0, stock(productId))
        assertEquals(false, canCancel(second.orderId))
    }

    @Test
    fun `a missing or too long reason, a customer token and an unknown order are rejected`() {
        val placed = paidOrder()

        cancel(placed.orderId, reason = null).andExpect { responseCode("106") }
        cancel(placed.orderId, reason = "   ").andExpect { responseCode("106") }
        cancel(placed.orderId, reason = "x".repeat(501)).andExpect { responseCode("106") }
        cancel(placed.orderId, withToken = token).andExpect { responseCode("107") }
        cancel(999_999_999).andExpect {
            responseCode("25")
            jsonPath("$.responseMessage") { value("Order not found") }
        }
        assertEquals("CONFIRMED" to "PAID", orderState(placed.orderId))
    }
}
