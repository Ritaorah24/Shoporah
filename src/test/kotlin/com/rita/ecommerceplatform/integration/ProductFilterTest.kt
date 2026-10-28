package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

/** Price, stock and sort filters on the product lists (checkpoint 2.2, Step 2). */
class ProductFilterTest : IntegrationTestBase() {

    private lateinit var adminToken: String

    // Each test filters by its own category, so other tests' products never appear in the results.
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
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

    private fun createProduct(price: Int, discountPrice: Int? = null, stock: Int = 10, name: String = "Filter phone"): Long {
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

    private fun ids(url: String, vararg params: Pair<String, Any>, admin: Boolean = false): List<Long> {
        val result = mockMvc.get(url) {
            if (admin) header("Authorization", "Bearer $adminToken")
            param("categoryId", categoryId.toString())
            params.forEach { (name, value) -> param(name, value.toString()) }
        }.andExpect { status { isOk() } }.andReturn()
        return readJson(result)["content"].toList().map { it["productId"].asLong() }
    }

    private fun publicIds(vararg params: Pair<String, Any>) = ids("/api/products", *params)

    private fun expectBadRequest(message: String?, vararg params: Pair<String, String>) {
        mockMvc.get("/api/products") {
            params.forEach { (name, value) -> param(name, value) }
        }.andExpect {
            status { isBadRequest() }
            if (message != null) jsonPath("$.message") { value(message) }
        }
    }

    @Test
    fun `the price range uses the selling price, so a discounted product counts at its discount price`() {
        val cheap = createProduct(price = 1000)
        // Listed at 5000 but sold at 3000: inside a 2000-4000 range.
        val discounted = createProduct(price = 5000, discountPrice = 3000)
        val expensive = createProduct(price = 6000)

        assertEquals(setOf(discounted), publicIds("minPrice" to 2000, "maxPrice" to 4000).toSet())
        // Both ends are inclusive.
        assertEquals(setOf(cheap, discounted), publicIds("minPrice" to 1000, "maxPrice" to 3000).toSet())
        assertEquals(setOf(discounted, expensive), publicIds("minPrice" to 3000).toSet())
        assertEquals(setOf(cheap), publicIds("maxPrice" to 2999.99).toSet())
    }

    @Test
    fun `inStock=true hides sold-out products, false shows everything`() {
        val inStock = createProduct(price = 1000, stock = 3)
        val soldOut = createProduct(price = 1000, stock = 0)

        assertEquals(setOf(inStock), publicIds("inStock" to true).toSet())
        assertEquals(setOf(inStock, soldOut), publicIds("inStock" to false).toSet())
        assertEquals(setOf(inStock, soldOut), publicIds().toSet())
    }

    @Test
    fun `price_asc and price_desc order by selling price, and sort ignores case`() {
        val mid = createProduct(price = 2000)
        val low = createProduct(price = 5000, discountPrice = 500)
        val high = createProduct(price = 9000)

        assertEquals(listOf(low, mid, high), publicIds("sort" to "price_asc"))
        assertEquals(listOf(high, mid, low), publicIds("sort" to "price_desc"))
        assertEquals(listOf(low, mid, high), publicIds("sort" to "PRICE_ASC"))
    }

    @Test
    fun `products with the same price keep one order across pages`() {
        val created = List(3) { createProduct(price = 1500) }

        val page0 = publicIds("sort" to "price_asc", "size" to 2, "page" to 0)
        val page1 = publicIds("sort" to "price_asc", "size" to 2, "page" to 1)

        // Ties fall back to productid, so every product appears exactly once.
        assertEquals(created.sorted(), page0 + page1)
    }

    @Test
    fun `without sort the list is still newest first`() {
        val first = createProduct(price = 1000)
        val second = createProduct(price = 3000)
        val third = createProduct(price = 2000)

        assertEquals(listOf(third, second, first), publicIds())
        assertEquals(listOf(third, second, first), publicIds("sort" to "newest"))
    }

    @Test
    fun `filters combine with search, and totalElements counts only the matches`() {
        val match = createProduct(price = 2000, name = "Blue kettle")
        createProduct(price = 9000, name = "Blue kettle deluxe")
        createProduct(price = 2000, name = "Red toaster")
        createProduct(price = 2000, stock = 0, name = "Blue kettle mini")

        mockMvc.get("/api/products") {
            param("categoryId", categoryId.toString())
            param("search", "kettle")
            param("maxPrice", "5000")
            param("inStock", "true")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(1) }
            jsonPath("$.content[0].productId") { value(match) }
            jsonPath("$.totalElements") { value(1) }
        }
    }

    @Test
    fun `bad filter values are rejected with 400`() {
        expectBadRequest("minPrice and maxPrice must not be negative", "minPrice" to "-1")
        expectBadRequest("minPrice and maxPrice must not be negative", "maxPrice" to "-0.01")
        expectBadRequest("minPrice must not be greater than maxPrice", "minPrice" to "5000", "maxPrice" to "1000")
        expectBadRequest("sort must be one of newest, price_asc, price_desc", "sort" to "name")
        // Not a number: Spring's type-mismatch handling.
        expectBadRequest(null, "minPrice" to "abc")
    }

    @Test
    fun `the admin list accepts the same filters`() {
        val low = createProduct(price = 1000)
        val soldOut = createProduct(price = 2000, stock = 0)
        val high = createProduct(price = 3000)

        assertEquals(listOf(high, low), ids("/api/admin/products", "inStock" to true, "sort" to "price_desc", admin = true))
        assertEquals(listOf(soldOut), ids("/api/admin/products", "minPrice" to 1500, "maxPrice" to 2500, admin = true))

        mockMvc.get("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            param("sort", "cheapest")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("sort must be one of newest, price_asc, price_desc") }
        }
    }
}
