package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.test.assertEquals

/** The customer's order history and details (checkpoint 1.3, Step 6). Each test uses a fresh customer. */
class OrderHistoryTest : IntegrationTestBase() {

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

    private fun createProduct(price: Int): Long {
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

    /** Fills the cart with `quantity` of a new product and checks out; returns the checkout response. */
    private fun placeOrder(price: Int = 1000, quantity: Int = 1): JsonNode {
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to createProduct(price), "quantity" to quantity)
        }.andExpect { status { isOk() } }
        val result = mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", UUID.randomUUID().toString())
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)
    }

    private fun listOrders(vararg params: Pair<String, Any>, withToken: String = token) =
        mockMvc.get("/api/customer/orders") {
            header("Authorization", "Bearer $withToken")
            params.forEach { (name, value) -> param(name, value.toString()) }
        }

    @Test
    fun `a new customer has no orders`() {
        listOrders().andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(0) }
            jsonPath("$.totalElements") { value(0) }
        }
    }

    @Test
    fun `the list shows summaries, newest first`() {
        val first = placeOrder(price = 1000, quantity = 2)
        val second = placeOrder(price = 300, quantity = 3)

        val result = listOrders().andExpect {
            status { isOk() }
            jsonPath("$.totalElements") { value(2) }
            jsonPath("$.content[0].orderId") { value(second["orderId"].asLong()) }
            jsonPath("$.content[0].reference") { value(second["reference"].asString()) }
            jsonPath("$.content[0].status") { value("PENDING") }
            jsonPath("$.content[0].paymentStatus") { value("UNPAID") }
            jsonPath("$.content[0].total") { value(900.00) }
            jsonPath("$.content[0].itemCount") { value(3) }
            jsonPath("$.content[1].orderId") { value(first["orderId"].asLong()) }
            jsonPath("$.content[1].total") { value(2000.00) }
            jsonPath("$.content[1].itemCount") { value(2) }
        }.andReturn()

        // Only the summary fields: items, address and history are in the details.
        assertEquals(
            setOf("orderId", "reference", "status", "paymentStatus", "total", "itemCount", "createdAt"),
            readJson(result)["content"][0].propertyNames().asSequence().toSet(),
        )
    }

    @Test
    fun `the list is paged with the page size capped at 50`() {
        val orders = (1..3).map { placeOrder() }

        listOrders("size" to 2).andExpect {
            jsonPath("$.size") { value(2) }
            jsonPath("$.totalElements") { value(3) }
            jsonPath("$.totalPages") { value(2) }
            jsonPath("$.content[0].orderId") { value(orders[2]["orderId"].asLong()) }
            jsonPath("$.content[1].orderId") { value(orders[1]["orderId"].asLong()) }
        }
        listOrders("page" to 1, "size" to 2).andExpect {
            jsonPath("$.content.length()") { value(1) }
            jsonPath("$.content[0].orderId") { value(orders[0]["orderId"].asLong()) }
        }
        listOrders("size" to 1000).andExpect { jsonPath("$.size") { value(50) } }
    }

    @Test
    fun `the details match what checkout returned, including the status history`() {
        val placed = placeOrder(price = 1000, quantity = 2)
        val orderId = placed["orderId"].asLong()

        // Checkout already returns the timeline.
        assertEquals(1, placed["statusHistory"].size())
        assertEquals("PENDING", placed["statusHistory"][0]["status"].asString())
        assertEquals("Order placed", placed["statusHistory"][0]["note"].asString())

        val result = mockMvc.get("/api/customer/orders/$orderId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].quantity") { value(2) }
            jsonPath("$.total") { value(2000.00) }
            jsonPath("$.deliveryAddress.city") { value("Ikeja") }
            jsonPath("$.statusHistory[0].changedAt") { isString() }
        }.andReturn()

        assertEquals(placed, readJson(result))
    }

    @Test
    fun `another customer's order is not found and not listed`() {
        val orderId = placeOrder()["orderId"].asLong()
        val other = newCustomerToken()

        mockMvc.get("/api/customer/orders/$orderId") {
            header("Authorization", "Bearer $other")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.message") { value("Order not found") }
        }
        listOrders(withToken = other).andExpect { jsonPath("$.totalElements") { value(0) } }
    }

    @Test
    fun `an unknown order returns 404`() {
        mockMvc.get("/api/customer/orders/999999") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `order history needs a customer token`() {
        mockMvc.get("/api/customer/orders").andExpect { status { isUnauthorized() } }
        listOrders(withToken = adminToken).andExpect { status { isForbidden() } }
    }
}
