package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Admin product management (checkpoint 1.2, Step 3). */
class ProductAdminTest : IntegrationTestBase() {

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
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun productBody(vararg overrides: Pair<String, Any?>): String {
        val fields = mutableMapOf<String, Any?>(
            "name" to "Test phone",
            "sku" to unique("sku"),
            "categoryId" to categoryId,
            "price" to 1000,
            "stockQuantity" to 10,
        )
        overrides.forEach { (key, value) -> fields[key] = value }
        return jsonMapper.writeValueAsString(fields)
    }

    private fun postProduct(body: String) = mockMvc.post("/api/admin/products") {
        header("Authorization", "Bearer $adminToken")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun createProduct(vararg overrides: Pair<String, Any?>): Long {
        val result = postProduct(productBody(*overrides)).andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["productId"].asLong()
    }

    private fun patchProduct(productId: Long, body: String) = mockMvc.patch("/api/admin/products/$productId") {
        header("Authorization", "Bearer $adminToken")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    /** Inventory log rows for a product, oldest first, as "TYPE change after". */
    private fun movements(productId: Long): List<String> =
        jdbcClient.sql(
            """
            SELECT inventorymovementtype, inventorymovementquantitychange, inventorymovementstockafter
            FROM public.ec_inventory_movement
            WHERE inventorymovementproductid = :productId
            ORDER BY inventorymovementid
            """.trimIndent(),
        )
            .param("productId", productId)
            .query { rs, _ -> "${rs.getString(1).uppercase()} ${rs.getInt(2)} ${rs.getInt(3)}" }
            .list()

    private fun listedProductIds(vararg params: Pair<String, Any>): List<Long> {
        val result = mockMvc.get("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            params.forEach { (name, value) -> param(name, value.toString()) }
        }.andExpect { status { isOk() } }.andReturn()
        return readJson(result)["content"].toList().map { it["productId"].asLong() }
    }

    @Test
    fun `create stores the product, uppercases the SKU and logs the initial stock`() {
        val sku = unique("abc")
        val result = postProduct(productBody("sku" to "  $sku  ", "price" to 1500.50, "discountPrice" to 1200)).andExpect {
            status { isCreated() }
            jsonPath("$.sku") { value(sku.uppercase()) }
            jsonPath("$.categoryId") { value(categoryId) }
            jsonPath("$.categoryName") { isString() }
            jsonPath("$.price") { value(1500.50) }
            jsonPath("$.discountPrice") { value(1200.00) }
            jsonPath("$.sellingPrice") { value(1200.00) }
            jsonPath("$.stockQuantity") { value(10) }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.createdByAdminId") { value(seedAdminId()) }
        }.andReturn()

        assertEquals(listOf("RESTOCK 10 10"), movements(readJson(result)["productId"].asLong()))
    }

    @Test
    fun `create with no stock logs nothing and selling price falls back to price`() {
        val result = postProduct(productBody("stockQuantity" to null)).andExpect {
            status { isCreated() }
            jsonPath("$.stockQuantity") { value(0) }
            jsonPath("$.discountPrice") { value(null) }
            jsonPath("$.sellingPrice") { value(1000.00) }
        }.andReturn()

        assertEquals(emptyList(), movements(readJson(result)["productId"].asLong()))
    }

    @Test
    fun `a SKU already used in any letter case returns 409`() {
        val sku = unique("dup")
        createProduct("sku" to sku.uppercase())

        postProduct(productBody("sku" to sku.lowercase())).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("SKU is already in use") }
        }
    }

    @Test
    fun `invalid fields return 400 with field errors`() {
        postProduct(productBody("sku" to "has space")).andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors[0].field") { value("sku") }
        }
        postProduct(productBody("price" to 0)).andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors[0].field") { value("price") }
            jsonPath("$.fieldErrors[0].message") { value("must be greater than 0") }
        }
        postProduct(productBody("price" to 10.555)).andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors[0].field") { value("price") }
        }
        postProduct(productBody("stockQuantity" to -1)).andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors[0].field") { value("stockQuantity") }
        }
        postProduct(productBody("name" to null, "categoryId" to null)).andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors.length()") { value(2) }
        }
    }

    @Test
    fun `a discount not below the price returns 400`() {
        postProduct(productBody("discountPrice" to 1000)).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("discountPrice must be less than price") }
        }
    }

    @Test
    fun `the category must exist and not be deleted, but may be inactive`() {
        postProduct(productBody("categoryId" to 999999)).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Category 999999 does not exist") }
        }

        val deletedCategory = createCategory()
        mockMvc.delete("/api/admin/categories/$deletedCategory") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { status { isNoContent() } }
        postProduct(productBody("categoryId" to deletedCategory)).andExpect { status { isBadRequest() } }

        mockMvc.patch("/api/admin/categories/$categoryId/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to "INACTIVE")
        }.andExpect { status { isOk() } }
        postProduct(productBody()).andExpect { status { isCreated() } }
    }

    @Test
    fun `PATCH changes only the fields sent`() {
        val productId = createProduct("description" to "Old")
        patchProduct(productId, json("name" to "New name")).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("New name") }
            jsonPath("$.description") { value("Old") }
            jsonPath("$.price") { value(1000.00) }
            jsonPath("$.stockQuantity") { value(10) }
        }
        patchProduct(productId, json("description" to "")).andExpect {
            jsonPath("$.description") { value(null) }
        }
    }

    @Test
    fun `PATCH checks the discount against the merged price`() {
        val productId = createProduct("discountPrice" to 800)

        // Only the price is sent, but it would fall below the existing discount.
        patchProduct(productId, json("price" to 700)).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("discountPrice must be less than price") }
        }
    }

    @Test
    fun `clearDiscountPrice removes the discount, and cannot be combined with a new one`() {
        val productId = createProduct("discountPrice" to 800)

        patchProduct(productId, json("discountPrice" to 500, "clearDiscountPrice" to true)).andExpect {
            status { isBadRequest() }
            content { string(containsString("send either discountPrice or clearDiscountPrice, not both")) }
        }
        patchProduct(productId, json("clearDiscountPrice" to true)).andExpect {
            status { isOk() }
            jsonPath("$.discountPrice") { value(null) }
            jsonPath("$.sellingPrice") { value(1000.00) }
        }
    }

    @Test
    fun `every stock change is logged with its direction and the stock after it`() {
        val productId = createProduct("stockQuantity" to 50)

        patchProduct(productId, json("stockQuantity" to 45)).andExpect { status { isOk() } }
        patchProduct(productId, json("stockQuantity" to 60)).andExpect { status { isOk() } }
        // No stock change: no log row.
        patchProduct(productId, json("name" to "Renamed")).andExpect { status { isOk() } }
        patchProduct(productId, json("stockQuantity" to 60)).andExpect { status { isOk() } }

        assertEquals(listOf("RESTOCK 50 50", "ADJUSTMENT -5 45", "RESTOCK 15 60"), movements(productId))
    }

    @Test
    fun `PATCH rejects unknown fields and empty bodies`() {
        val productId = createProduct()
        patchProduct(productId, json("status" to "INACTIVE")).andExpect {
            status { isBadRequest() }
            content { string(containsString("use PATCH .../status to change the status")) }
        }
        patchProduct(productId, "{}").andExpect {
            status { isBadRequest() }
            content { string(containsString("send at least one field to update")) }
        }
    }

    @Test
    fun `PATCH to another product's SKU returns 409, its own SKU in another case is fine`() {
        val ownSku = unique("own")
        val productId = createProduct("sku" to ownSku)
        val otherSku = unique("other")
        createProduct("sku" to otherSku)

        patchProduct(productId, json("sku" to ownSku.lowercase())).andExpect {
            status { isOk() }
            jsonPath("$.sku") { value(ownSku.uppercase()) }
        }
        patchProduct(productId, json("sku" to otherSku)).andExpect { status { isConflict() } }
    }

    @Test
    fun `status changes and the admin list status filter`() {
        val activeId = createProduct()
        val inactiveId = createProduct()
        mockMvc.patch("/api/admin/products/$inactiveId/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to "inactive")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("INACTIVE") }
        }

        assertEquals(setOf(activeId, inactiveId), listedProductIds("categoryId" to categoryId).toSet())
        assertEquals(listOf(activeId), listedProductIds("categoryId" to categoryId, "status" to "ACTIVE"))
        assertEquals(listOf(inactiveId), listedProductIds("categoryId" to categoryId, "status" to "inactive"))

        mockMvc.get("/api/admin/products?status=DELETED") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a deleted product is gone for good but its SKU stays taken`() {
        val sku = unique("gone")
        val productId = createProduct("sku" to sku)

        mockMvc.delete("/api/admin/products/$productId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/admin/products/$productId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.message") { value("Product not found") }
        }
        patchProduct(productId, json("name" to "Back?")).andExpect { status { isNotFound() } }
        mockMvc.delete("/api/admin/products/$productId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { status { isNotFound() } }

        assertTrue(productId !in listedProductIds("categoryId" to categoryId))
        postProduct(productBody("sku" to sku)).andExpect { status { isConflict() } }

        // With its only product deleted, the category can now be deleted (D2).
        mockMvc.delete("/api/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `the list is paged, newest first, with the page size capped at 50`() {
        val ids = (1..3).map { createProduct() }

        val result = mockMvc.get("/api/admin/products?categoryId=$categoryId&page=0&size=2") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(2) }
            jsonPath("$.totalElements") { value(3) }
            jsonPath("$.totalPages") { value(2) }
        }.andReturn()
        assertEquals(listOf(ids[2], ids[1]), readJson(result)["content"].toList().map { it["productId"].asLong() })
        assertEquals(listOf(ids[0]), listedProductIds("categoryId" to categoryId, "page" to "1", "size" to "2"))

        mockMvc.get("/api/admin/products?size=1000&page=-3") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            jsonPath("$.size") { value(50) }
            jsonPath("$.page") { value(0) }
        }
    }

    @Test
    fun `search matches part of the name, ignoring case, with wildcards taken literally`() {
        val match = createProduct("name" to "Samsung Galaxy S24")
        createProduct("name" to "Apple iPhone 15")
        val percent = createProduct("name" to "Phone 50% off")

        assertEquals(listOf(match), listedProductIds("categoryId" to categoryId, "search" to "gALaxy"))
        assertEquals(listOf(percent), listedProductIds("categoryId" to categoryId, "search" to "50%"))
    }

    @Test
    fun `customers cannot manage products`() {
        mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer ${newCustomerToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = productBody()
        }.andExpect { status { isForbidden() } }

        mockMvc.get("/api/admin/products").andExpect { status { isUnauthorized() } }
    }
}
