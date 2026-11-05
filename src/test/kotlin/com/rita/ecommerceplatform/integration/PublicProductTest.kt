package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

/** Public product browsing (checkpoint 1.2, Step 4). Public requests send no token. */
class PublicProductTest : IntegrationTestBase() {

    private lateinit var adminToken: String

    // Each test gets its own category, so listing counts are not affected by other tests.
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
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun createProduct(
        name: String = "Test phone",
        stock: Int = 10,
        lowStockThreshold: Int? = null,
        inCategory: Int = categoryId,
    ): Long {
        val result = mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to name,
                "sku" to unique("sku"),
                "categoryId" to inCategory,
                "price" to 1000,
                "discountPrice" to 900,
                "stockQuantity" to stock,
                "lowStockThreshold" to lowStockThreshold,
            )
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["productId"].asLong()
    }

    private fun setStatus(kind: String, id: Number, status: String) {
        mockMvc.patch("/api/admin/$kind/$id/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to status)
        }.andExpect { responseCode("00") }
    }

    private fun publicIds(vararg params: Pair<String, Any>): List<Long> {
        val result = mockMvc.get("/api/products") {
            params.forEach { (name, value) -> param(name, value.toString()) }
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["content"].toList().map { it["productId"].asLong() }
    }

    private fun expectPublicDetails(productId: Long, visible: Boolean) {
        mockMvc.get("/api/products/$productId").andExpect {
            if (visible) {
                responseCode("00")
            } else {
                responseCode("25")
                jsonPath("$.responseMessage") { value("Product not found") }
            }
        }
    }

    @Test
    fun `an active product in an active category is visible without a token`() {
        val productId = createProduct(name = "Visible phone")

        assertEquals(listOf(productId), publicIds("categoryId" to categoryId))
        mockMvc.get("/api/products/$productId").andExpect {
            responseCode("00")
            jsonPath("$.data.productId") { value(productId) }
            jsonPath("$.data.name") { value("Visible phone") }
            jsonPath("$.data.categoryId") { value(categoryId) }
            jsonPath("$.data.categoryName") { isString() }
            jsonPath("$.data.price") { value(1000.00) }
            jsonPath("$.data.discountPrice") { value(900.00) }
            jsonPath("$.data.sellingPrice") { value(900.00) }
            jsonPath("$.data.availability") { value("IN_STOCK") }
        }
    }

    @Test
    fun `the public response has no admin-only fields`() {
        val productId = createProduct()
        val result = mockMvc.get("/api/products/$productId").andExpect { responseCode("00") }.andReturn()

        // primaryImageUrl and images since checkpoint 2.2 Step 8b; still no SKU, stock, status or timestamps.
        assertEquals(
            setOf(
                "productId", "name", "description", "categoryId", "categoryName", "price", "discountPrice", "sellingPrice",
                "availability", "primaryImageUrl", "images",
            ),
            readJson(result).propertyNames().asSequence().toSet(),
        )
    }

    @Test
    fun `an inactive product is hidden until it is reactivated`() {
        val productId = createProduct()

        setStatus("products", productId, "INACTIVE")
        assertEquals(emptyList(), publicIds("categoryId" to categoryId))
        expectPublicDetails(productId, visible = false)

        setStatus("products", productId, "ACTIVE")
        assertEquals(listOf(productId), publicIds("categoryId" to categoryId))
        expectPublicDetails(productId, visible = true)
    }

    @Test
    fun `a deleted product is never visible`() {
        val productId = createProduct()
        mockMvc.delete("/api/admin/products/$productId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { responseCode("00") }

        assertEquals(emptyList(), publicIds("categoryId" to categoryId))
        expectPublicDetails(productId, visible = false)
    }

    @Test
    fun `deactivating a category hides all its products`() {
        val name = unique("Hidden")
        val productId = createProduct(name = name)

        setStatus("categories", categoryId, "INACTIVE")
        expectPublicDetails(productId, visible = false)
        // The filter on a hidden category gives an empty page, not an error.
        assertEquals(emptyList(), publicIds("categoryId" to categoryId))
        // Not visible in the unfiltered listing either.
        assertEquals(emptyList(), publicIds("search" to name))

        setStatus("categories", categoryId, "ACTIVE")
        expectPublicDetails(productId, visible = true)
    }

    @Test
    fun `availability is shown instead of the stock count`() {
        val outOfStock = createProduct(stock = 0, lowStockThreshold = 5)
        val low = createProduct(stock = 3, lowStockThreshold = 5)
        val atThreshold = createProduct(stock = 5, lowStockThreshold = 5)
        val noThreshold = createProduct(stock = 3)
        val plenty = createProduct(stock = 50, lowStockThreshold = 5)

        fun availabilityOf(productId: Long): String {
            val result = mockMvc.get("/api/products/$productId").andExpect { responseCode("00") }.andReturn()
            return readJson(result)["availability"].asString()
        }

        assertEquals("OUT_OF_STOCK", availabilityOf(outOfStock))
        assertEquals("LOW_STOCK", availabilityOf(low))
        assertEquals("LOW_STOCK", availabilityOf(atThreshold))
        assertEquals("IN_STOCK", availabilityOf(noThreshold))
        assertEquals("IN_STOCK", availabilityOf(plenty))

        // Out-of-stock products are still listed, marked as such (D4).
        assertEquals(true, outOfStock in publicIds("categoryId" to categoryId))
    }

    @Test
    fun `the listing is paged, newest first, with the page size capped at 50`() {
        val ids = (1..3).map { createProduct() }

        mockMvc.get("/api/products") {
            param("categoryId", categoryId.toString())
            param("size", "2")
        }.andExpect {
            responseCode("00")
            jsonPath("$.data.size") { value(2) }
            jsonPath("$.data.totalElements") { value(3) }
            jsonPath("$.data.totalPages") { value(2) }
            jsonPath("$.data.content[0].productId") { value(ids[2]) }
            jsonPath("$.data.content[1].productId") { value(ids[1]) }
        }
        assertEquals(listOf(ids[0]), publicIds("categoryId" to categoryId, "page" to 1, "size" to 2))

        mockMvc.get("/api/products") { param("size", "1000") }.andExpect {
            jsonPath("$.data.size") { value(50) }
        }
    }

    @Test
    fun `search and category filter narrow the listing`() {
        val galaxy = createProduct(name = "Samsung Galaxy S24")
        createProduct(name = "Apple iPhone 15")
        val otherCategory = createCategory()
        val galaxyElsewhere = createProduct(name = "Galaxy Tab", inCategory = otherCategory)

        assertEquals(listOf(galaxy), publicIds("categoryId" to categoryId, "search" to "GALAXY"))
        assertEquals(listOf(galaxyElsewhere), publicIds("categoryId" to otherCategory, "search" to "galaxy"))
        assertEquals(emptyList(), publicIds("categoryId" to 999999))
    }

    @Test
    fun `an unknown product returns 404 and only GET is public`() {
        expectPublicDetails(999999, visible = false)

        mockMvc.post("/api/products") {
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to "Sneaky")
        }.andExpect { responseCode("115") }
    }
}
