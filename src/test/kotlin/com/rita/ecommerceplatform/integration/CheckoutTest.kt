package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/** Checkout: cart to order (checkpoint 1.3, Step 5). Each test uses a fresh customer and its own category. */
class CheckoutTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private lateinit var token: String
    private var customerId: Long = 0
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        val email = uniqueEmail()
        customerId = registerCustomer(email)
        token = loginCustomer(email)
        categoryId = createCategory()
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

    private fun createProduct(name: String = unique("Phone"), price: Int = 1000, discountPrice: Int? = null, stock: Int = 10): Long {
        val result = mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to name,
                "sku" to unique("sku"),
                "categoryId" to categoryId,
                "price" to price,
                "discountPrice" to discountPrice,
                "stockQuantity" to stock,
            )
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["productId"].asLong()
    }

    private fun adminPatch(url: String, body: String) {
        mockMvc.patch(url) {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isOk() } }
    }

    private fun createAddress(withToken: String = token, city: String = "Ikeja", isDefault: Boolean? = null): Long {
        val result = mockMvc.post("/api/customer/addresses") {
            header("Authorization", "Bearer $withToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "recipientName" to "Ada Obi",
                "recipientPhoneNumber" to "08012345678",
                "addressLine1" to "12 Allen Avenue",
                "city" to city,
                "state" to "Lagos",
                "country" to "Nigeria",
                "isDefault" to isDefault,
            )
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["addressId"].asLong()
    }

    private fun addToCart(productId: Long, quantity: Int = 1) {
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to productId, "quantity" to quantity)
        }.andExpect { status { isOk() } }
    }

    private fun checkout(key: String? = UUID.randomUUID().toString(), body: String? = null, withToken: String = token): ResultActionsDsl =
        mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer $withToken")
            key?.let { header("Idempotency-Key", it) }
            body?.let {
                contentType = MediaType.APPLICATION_JSON
                content = it
            }
        }

    private fun orderCount(): Long =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_order WHERE ordercustomerid = :customerId")
            .param("customerId", customerId)
            .query(Long::class.javaObjectType)
            .single()

    private fun cartQuantities(): List<Int> {
        val result = mockMvc.get("/api/customer/cart") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }.andReturn()
        return readJson(result)["items"].toList().map { it["quantity"].asInt() }
    }

    @Test
    fun `checkout creates a pending order priced from the database and empties the cart`() {
        createAddress(city = "Ikeja")
        val phone = createProduct(name = unique("Phone"), price = 1000, discountPrice = 800)
        val case = createProduct(name = unique("Case"), price = 500)
        addToCart(phone, 2)
        addToCart(case, 1)

        val result = checkout().andExpect {
            status { isCreated() }
            jsonPath("$.orderId") { isNumber() }
            jsonPath("$.reference") { value(matchesPattern("^ORD-\\d{8}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$")) }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.paymentStatus") { value("UNPAID") }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].productId") { value(phone) }
            jsonPath("$.items[0].unitPrice") { value(800.00) }
            jsonPath("$.items[0].quantity") { value(2) }
            jsonPath("$.items[0].lineTotal") { value(1600.00) }
            jsonPath("$.items[0].productSku") { isString() }
            jsonPath("$.items[1].unitPrice") { value(500.00) }
            jsonPath("$.subtotal") { value(2100.00) }
            jsonPath("$.deliveryFee") { value(0.00) }
            jsonPath("$.discountAmount") { value(0.00) }
            jsonPath("$.total") { value(2100.00) }
            jsonPath("$.deliveryAddress.city") { value("Ikeja") }
            jsonPath("$.deliveryAddress.recipientName") { value("Ada Obi") }
        }.andReturn()

        assertEquals(emptyList(), cartQuantities())

        // The first history row: no previous status, PENDING, made by the customer.
        val orderId = readJson(result)["orderId"].asLong()
        val history = jdbcClient.sql(
            """
            SELECT orderstatushistoryfromstatus, orderstatushistorytostatus, orderstatushistorychangedbycustomerid
            FROM public.ec_order_status_history WHERE orderstatushistoryorderid = :orderId
            """.trimIndent(),
        )
            .param("orderId", orderId)
            .query { rs, _ -> "${rs.getString(1)} ${rs.getString(2)} ${rs.getLong(3)}" }
            .list()
        assertEquals(listOf("null PENDING $customerId"), history)
    }

    @Test
    fun `prices and totals sent by the client are ignored`() {
        createAddress()
        addToCart(createProduct(price = 1000), 2)

        checkout(body = """{"price": 1, "unitPrice": 1, "subtotal": 1, "total": 1, "discountAmount": 1999}""").andExpect {
            status { isCreated() }
            jsonPath("$.items[0].unitPrice") { value(1000.00) }
            jsonPath("$.subtotal") { value(2000.00) }
            jsonPath("$.discountAmount") { value(0.00) }
            jsonPath("$.total") { value(2000.00) }
        }
    }

    @Test
    fun `retrying with the same key returns the same order and never creates a second one`() {
        createAddress()
        addToCart(createProduct())
        val key = UUID.randomUUID().toString()

        val first = readJson(checkout(key).andExpect { status { isCreated() } }.andReturn())
        // The cart is empty now, but the retry still returns the original order.
        val retry = readJson(checkout(key).andExpect { status { isOk() } }.andReturn())

        assertEquals(first["orderId"].asLong(), retry["orderId"].asLong())
        assertEquals(first["reference"].asString(), retry["reference"].asString())
        assertEquals(1, orderCount())

        // A new key with an empty cart is a new attempt, and fails.
        checkout().andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Cart is empty") }
        }
        // A new key with a refilled cart is a second order.
        addToCart(createProduct())
        checkout().andExpect { status { isCreated() } }
        assertEquals(2, orderCount())
    }

    @Test
    fun `two simultaneous requests with the same key create only one order`() {
        createAddress()
        addToCart(createProduct())
        val key = UUID.randomUUID().toString()

        val pool = Executors.newFixedThreadPool(2)
        val statuses = try {
            pool.invokeAll(List(2) { Callable { checkout(key).andReturn().response.status } }).map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(listOf(200, 201), statuses.sorted())
        assertEquals(1, orderCount())
    }

    @Test
    fun `the same key used by two customers gives each their own order`() {
        createAddress()
        addToCart(createProduct())
        val key = UUID.randomUUID().toString()
        checkout(key).andExpect { status { isCreated() } }

        val otherToken = newCustomerToken()
        createAddress(withToken = otherToken)
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer $otherToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to createProduct())
        }.andExpect { status { isOk() } }
        checkout(key, withToken = otherToken).andExpect { status { isCreated() } }
    }

    @Test
    fun `the order keeps its prices and address after the product and address change`() {
        val addressId = createAddress(city = "Ikeja")
        val productId = createProduct(price = 1000)
        addToCart(productId)
        val key = UUID.randomUUID().toString()
        checkout(key).andExpect { status { isCreated() } }

        adminPatch("/api/admin/products/$productId", json("price" to 5000, "name" to "Renamed"))
        mockMvc.patch("/api/customer/addresses/$addressId") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("city" to "Yaba")
        }.andExpect { status { isOk() } }

        // The retry reads the stored order back from the database.
        checkout(key).andExpect {
            status { isOk() }
            jsonPath("$.items[0].unitPrice") { value(1000.00) }
            jsonPath("$.items[0].productName") { value(not("Renamed")) }
            jsonPath("$.total") { value(1000.00) }
            jsonPath("$.deliveryAddress.city") { value("Ikeja") }
        }

        // Deleting the address does not touch the order either.
        mockMvc.delete("/api/customer/addresses/$addressId") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isNoContent() } }
        checkout(key).andExpect { jsonPath("$.deliveryAddress.city") { value("Ikeja") } }
    }

    @Test
    fun `an explicit address wins over the default`() {
        createAddress(city = "Ikeja")
        val other = createAddress(city = "Abuja")
        addToCart(createProduct())

        checkout(body = json("addressId" to other)).andExpect {
            status { isCreated() }
            jsonPath("$.deliveryAddress.city") { value("Abuja") }
        }
    }

    @Test
    fun `an address that is not the customer's own, or no address at all, is rejected`() {
        addToCart(createProduct())
        val othersAddress = createAddress(withToken = newCustomerToken())

        checkout(body = json("addressId" to othersAddress)).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Address $othersAddress does not exist") }
        }
        // No address saved, so no default either.
        checkout().andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("No delivery address: send addressId or set a default address") }
        }
        assertEquals(0, orderCount())
        assertEquals(listOf(1), cartQuantities())
    }

    @Test
    fun `an empty cart cannot be checked out`() {
        createAddress()
        checkout().andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Cart is empty") }
        }
    }

    @Test
    fun `items that can no longer be bought stop the checkout and nothing is saved`() {
        createAddress()
        val lowered = createProduct(name = unique("Lowered"), stock = 10)
        val deactivated = createProduct(name = unique("Gone"))
        val fine = createProduct()
        addToCart(lowered, 5)
        addToCart(deactivated)
        addToCart(fine)

        adminPatch("/api/admin/products/$lowered", json("stockQuantity" to 3))
        adminPatch("/api/admin/products/$deactivated/status", json("status" to "INACTIVE"))

        checkout().andExpect {
            status { isConflict() }
            jsonPath("$.message") { value(containsString("Some items cannot be ordered")) }
            jsonPath("$.message") { value(containsString("Lowered-")) }
            jsonPath("$.message") { value(containsString("(INSUFFICIENT_STOCK)")) }
            jsonPath("$.message") { value(containsString("Gone-")) }
            jsonPath("$.message") { value(containsString("(PRODUCT_UNAVAILABLE)")) }
        }
        assertEquals(0, orderCount())
        assertEquals(listOf(5, 1, 1), cartQuantities())
    }

    @Test
    fun `a missing or malformed Idempotency-Key is rejected`() {
        createAddress()
        addToCart(createProduct())

        checkout(key = null).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Idempotency-Key header is required") }
        }
        checkout(key = "not-a-uuid").andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Idempotency-Key header must be a UUID") }
        }
        assertEquals(0, orderCount())
    }

    @Test
    fun `checkout needs a customer token`() {
        mockMvc.post("/api/customer/orders") {
            header("Idempotency-Key", UUID.randomUUID().toString())
        }.andExpect { status { isUnauthorized() } }
        checkout(withToken = adminToken).andExpect { status { isForbidden() } }
    }
}
