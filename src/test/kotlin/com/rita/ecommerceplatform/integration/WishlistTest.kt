package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/** The customer's wishlist (checkpoint 3.1, Step 3): add, view, remove, and move to the cart within the stock. */
class WishlistTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private lateinit var token: String
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        token = newCustomerToken()
        categoryId = readJson(
            mockMvc.post("/dev/admin/categories") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = json("name" to unique("Cat"))
            }.andExpect { responseCode("00") }.andReturn()
        )["categoryId"].asInt()
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createProduct(stock: Int = 10, name: String = unique("Phone"), lowStockThreshold: Int? = null): Long =
        readJson(
            mockMvc.post("/dev/admin/products") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = json(
                    "name" to name, "sku" to unique("sku"), "categoryId" to categoryId, "price" to 1000,
                    "discountPrice" to 900, "stockQuantity" to stock, "lowStockThreshold" to lowStockThreshold,
                )
            }.andExpect { responseCode("00") }.andReturn()
        )["productId"].asLong()

    private fun add(productId: Long, withToken: String = token): ResultActionsDsl =
        mockMvc.post("/dev/customer/wishlist/items") {
            header("Authorization", "Bearer $withToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to productId)
        }

    private fun wishlist(withToken: String = token): JsonNode =
        readJson(mockMvc.get("/dev/customer/wishlist") { header("Authorization", "Bearer $withToken") }
            .andExpect { responseCode("00") }.andReturn())

    private fun ids(list: JsonNode): List<Long> = list["items"].toList().map { it["productId"].asLong() }

    private fun move(productId: Long, quantity: Int? = null): ResultActionsDsl =
        mockMvc.post("/dev/customer/wishlist/items/$productId/move-to-cart") {
            header("Authorization", "Bearer $token")
            quantity?.let {
                contentType = MediaType.APPLICATION_JSON
                content = json("quantity" to it)
            }
        }

    private fun addToCart(productId: Long, quantity: Int) {
        mockMvc.post("/dev/customer/cart/items") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to productId, "quantity" to quantity)
        }.andExpect { responseCode("00") }
    }

    /** productId → quantity in the customer's cart. */
    private fun cart(): Map<Long, Int> =
        readJson(mockMvc.get("/dev/customer/cart") { header("Authorization", "Bearer $token") }.andReturn())["items"]
            .toList().associate { it["productId"].asLong() to it["quantity"].asInt() }

    private fun setProductStatus(productId: Long, status: String) {
        mockMvc.patch("/dev/admin/products/$productId/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to status)
        }.andExpect { responseCode("00") }
    }

    @Test
    fun `added products are listed newest first with their current price and availability`() {
        val first = createProduct(name = "Older phone")
        val second = createProduct(stock = 2, lowStockThreshold = 5)

        add(first).andExpect {
            responseCode("00")
            jsonPath("$.data.itemCount") { value(1) }
        }
        add(second).andExpect { responseCode("00") }

        val list = wishlist()
        assertEquals(listOf(second, first), ids(list))
        val older = list["items"][1]
        assertEquals("Older phone", older["name"].asString())
        assertEquals(1000.0, older["price"].asDouble())
        assertEquals(900.0, older["sellingPrice"].asDouble())
        assertEquals("IN_STOCK", older["availability"].asString())
        assertEquals(true, older["available"].asBoolean())
        assertEquals(true, older["imageUrl"].isNull)
        assertEquals("LOW_STOCK", list["items"][0]["availability"].asString())

        // A new price shows at once: the wishlist stores no price.
        mockMvc.patch("/dev/admin/products/$first") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("clearDiscountPrice" to true)
        }.andExpect { responseCode("00") }
        assertEquals(1000.0, wishlist()["items"][1]["sellingPrice"].asDouble())
    }

    @Test
    fun `adding twice keeps one entry, out of stock is allowed, hidden or unknown products are 404`() {
        val productId = createProduct()
        add(productId)
        val addedAt = wishlist()["items"][0]["addedAt"].asString()
        add(productId).andExpect {
            responseCode("00")
            jsonPath("$.data.itemCount") { value(1) }
            jsonPath("$.data.items[0].addedAt") { value(addedAt) }
        }

        val soldOut = createProduct(stock = 0)
        add(soldOut).andExpect {
            responseCode("00")
            jsonPath("$.data.items[0].availability") { value("OUT_OF_STOCK") }
        }

        val inactive = createProduct().also { setProductStatus(it, "INACTIVE") }
        add(inactive).andExpect {
            responseCode("25")
            jsonPath("$.responseMessage") { value("Product not found") }
        }
        add(999999).andExpect { responseCode("25") }
        mockMvc.post("/dev/customer/wishlist/items") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { responseCode("106") }
        assertEquals(2, wishlist()["itemCount"].asInt())
    }

    @Test
    fun `a product that becomes unavailable stays on the list, marked unavailable, and can be removed`() {
        val deactivated = createProduct()
        val deleted = createProduct()
        add(deactivated)
        add(deleted)

        setProductStatus(deactivated, "INACTIVE")
        mockMvc.delete("/dev/admin/products/$deleted") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("00") }

        val items = wishlist()["items"].toList()
        assertEquals(2, items.size)
        items.forEach {
            assertEquals(false, it["available"].asBoolean())
            assertEquals(true, it["availability"].isNull)
        }
        move(deactivated).andExpect {
            responseCode("25")
            jsonPath("$.responseMessage") { value("Product not found") }
        }
        assertEquals(2, wishlist()["itemCount"].asInt())

        mockMvc.delete("/dev/customer/wishlist/items/$deleted") { header("Authorization", "Bearer $token") }
            .andExpect { responseCode("00") }
        mockMvc.delete("/dev/customer/wishlist/items/$deleted") { header("Authorization", "Bearer $token") }.andExpect {
            responseCode("25")
            jsonPath("$.responseMessage") { value("Item not in wishlist") }
        }
        assertEquals(listOf(deactivated), ids(wishlist()))
    }

    @Test
    fun `moving puts the product in the cart and takes it off the wishlist`() {
        val one = createProduct()
        val three = createProduct()
        add(one)
        add(three)

        move(one).andExpect {
            responseCode("00")
            jsonPath("$.data.itemCount") { value(1) }
            jsonPath("$.data.items[0].productId") { value(three) }
        }
        addToCart(three, 2)
        move(three, quantity = 3).andExpect { jsonPath("$.data.itemCount") { value(0) } }

        assertEquals(mapOf(one to 1, three to 5), cart())
        move(one).andExpect {
            responseCode("25")
            jsonPath("$.responseMessage") { value("Item not in wishlist") }
        }
    }

    @Test
    fun `moving never goes above the stock, and a refused move changes nothing`() {
        val productId = createProduct(stock = 4)
        add(productId)

        move(productId, quantity = 5).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Only 4 available") }
        }
        addToCart(productId, 3)
        move(productId, quantity = 2).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Only 4 available") }
        }
        assertEquals(mapOf(productId to 3), cart())
        assertEquals(listOf(productId), ids(wishlist()))

        val soldOut = createProduct(stock = 0)
        add(soldOut)
        move(soldOut).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Out of stock") }
        }
        assertEquals(setOf(productId, soldOut), ids(wishlist()).toSet())
        move(productId, quantity = 0).andExpect { responseCode("106") }

        // Exactly the stock is fine.
        move(productId, quantity = 1).andExpect { responseCode("00") }
        assertEquals(mapOf(productId to 4), cart())
    }

    @Test
    fun `two moves of the same item at the same moment add it to the cart once`() {
        val productId = createProduct()
        add(productId)

        val pool = Executors.newFixedThreadPool(2)
        val statuses = try {
            pool.invokeAll(List(2) { Callable { move(productId, quantity = 2).andReturn().responseCodeOf() } }).map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(listOf("00", "25"), statuses.sorted())
        assertEquals(mapOf(productId to 2), cart())
        assertEquals(0, wishlist()["itemCount"].asInt())
    }

    @Test
    fun `a wishlist holds at most 100 products`() {
        // 100 products straight into the database, to keep the test fast.
        val productIds = jdbcClient.sql(
            """
            INSERT INTO public.ec_product (productcategoryid, productcreatedbyadminid, productname, productsku, productprice, productstockquantity)
            SELECT :categoryId, :adminId, 'Bulk ' || n, :prefix || n, 100, 5 FROM generate_series(1, 100) AS n
            RETURNING productid
            """
        ).param("categoryId", categoryId).param("adminId", seedAdminId()).param("prefix", unique("bulk"))
            .query(Long::class.javaObjectType).list().map { it!! }
        productIds.take(99).forEach { add(it).andExpect { responseCode("00") } }
        add(productIds[99]).andExpect { jsonPath("$.data.itemCount") { value(100) } }

        val extra = createProduct()
        add(extra).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("A wishlist can hold at most 100 products") }
        }
        // One already on the list is still fine.
        add(productIds[0]).andExpect { responseCode("00") }
    }

    @Test
    fun `each customer sees only their own wishlist, and other tokens are refused`() {
        val productId = createProduct()
        add(productId)
        val other = newCustomerToken()

        assertEquals(emptyList(), ids(wishlist(other)))
        mockMvc.delete("/dev/customer/wishlist/items/$productId") { header("Authorization", "Bearer $other") }
            .andExpect { responseCode("25") }
        assertEquals(listOf(productId), ids(wishlist()))

        mockMvc.get("/dev/customer/wishlist") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("107") }
        add(productId, withToken = adminToken).andExpect { responseCode("107") }
        mockMvc.get("/dev/customer/wishlist").andExpect { responseCode("115") }
    }
}
