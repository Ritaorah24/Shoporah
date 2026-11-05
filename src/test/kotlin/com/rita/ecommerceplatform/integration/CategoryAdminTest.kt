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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Admin category management and the public category list (checkpoint 1.2, Step 2). */
class CategoryAdminTest : IntegrationTestBase() {

    private lateinit var adminToken: String

    @BeforeEach
    fun loginAdmin() {
        adminToken = loginActiveAdmin()
    }

    // Category names are unique across the whole run (deleted names stay taken), so each test uses its own.
    private fun uniqueName(): String = "Cat-${UUID.randomUUID().toString().take(8)}"

    private fun createCategory(name: String = uniqueName(), description: String? = null): Int {
        val result = mockMvc.post("/dev/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to name, "description" to description)
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun setStatus(categoryId: Int, status: String) {
        mockMvc.patch("/dev/admin/categories/$categoryId/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to status)
        }.andExpect { responseCode("00") }
    }

    private fun publicCategoryIds(): List<Int> {
        val result = mockMvc.get("/dev/categories").andExpect { responseCode("00") }.andReturn()
        return readJson(result).toList().map { it["categoryId"].asInt() }
    }

    private fun adminCategoryIds(): List<Int> {
        val result = mockMvc.get("/dev/admin/categories") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result).toList().map { it["categoryId"].asInt() }
    }

    @Test
    fun `create trims the name, starts ACTIVE and records the creating admin`() {
        val name = uniqueName()
        mockMvc.post("/dev/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to "  $name  ", "description" to "Smartphones")
        }.andExpect {
            responseCode("00")
            jsonPath("$.data.name") { value(name) }
            jsonPath("$.data.description") { value("Smartphones") }
            jsonPath("$.data.status") { value("ACTIVE") }
            jsonPath("$.data.createdByAdminId") { value(seedAdminId()) }
        }
    }

    @Test
    fun `create with a blank name returns 400`() {
        mockMvc.post("/dev/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to "   ")
        }.andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].field") { value("name") }
        }
    }

    @Test
    fun `a name already used in any letter case returns 409`() {
        val name = uniqueName()
        createCategory(name)

        mockMvc.post("/dev/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to name.uppercase())
        }.andExpect {
            responseCode("117")
            jsonPath("$.responseMessage") { value("Category name is already in use") }
        }
    }

    @Test
    fun `public list shows only id, name and description`() {
        val categoryId = createCategory(description = "Visible")
        val result = mockMvc.get("/dev/categories").andExpect { responseCode("00") }.andReturn()
        val category = readJson(result).first { it["categoryId"].asInt() == categoryId }

        assertEquals(setOf("categoryId", "name", "description"), category.propertyNames().asSequence().toSet())
    }

    @Test
    fun `PATCH with an empty description clears it and keeps the name`() {
        val name = uniqueName()
        val categoryId = createCategory(name, description = "Old text")

        mockMvc.patch("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("description" to "")
        }.andExpect {
            responseCode("00")
            jsonPath("$.data.name") { value(name) }
            jsonPath("$.data.description") { value(null) }
        }
    }

    @Test
    fun `renaming to its own name is allowed but to another category's name returns 409`() {
        val name = uniqueName()
        val categoryId = createCategory(name)
        val otherName = uniqueName()
        createCategory(otherName)

        mockMvc.patch("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to name.lowercase())
        }.andExpect { responseCode("00") }

        mockMvc.patch("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to otherName)
        }.andExpect { responseCode("117") }
    }

    @Test
    fun `PATCH cannot change the status and needs at least one field`() {
        val categoryId = createCategory()

        mockMvc.patch("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to "INACTIVE")
        }.andExpect {
            responseCode("106")
            content { string(containsString("use PATCH .../status to change the status")) }
        }

        mockMvc.patch("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            responseCode("106")
            content { string(containsString("send at least one of name, description")) }
        }
    }

    @Test
    fun `deactivating hides the category from the public but not from admins, and reactivating shows it again`() {
        val categoryId = createCategory()
        assertTrue(categoryId in publicCategoryIds())

        // Any letter case is accepted.
        setStatus(categoryId, "inactive")
        assertFalse(categoryId in publicCategoryIds())
        assertTrue(categoryId in adminCategoryIds())

        setStatus(categoryId, "ACTIVE")
        assertTrue(categoryId in publicCategoryIds())
    }

    @Test
    fun `the status endpoint accepts only ACTIVE or INACTIVE`() {
        val categoryId = createCategory()
        mockMvc.patch("/dev/admin/categories/$categoryId/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to "DELETED")
        }.andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].field") { value("status") }
            jsonPath("$.data.fieldErrors[0].message") { value("must be ACTIVE or INACTIVE") }
        }
    }

    @Test
    fun `a deleted category is gone for good but its name stays taken`() {
        val name = uniqueName()
        val categoryId = createCategory(name)

        mockMvc.delete("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { responseCode("00") }

        mockMvc.get("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            responseCode("25")
            jsonPath("$.responseMessage") { value("Category not found") }
        }
        mockMvc.patch("/dev/admin/categories/$categoryId/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to "ACTIVE")
        }.andExpect { responseCode("25") }
        mockMvc.delete("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { responseCode("25") }

        assertFalse(categoryId in adminCategoryIds())
        assertFalse(categoryId in publicCategoryIds())

        mockMvc.post("/dev/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to name)
        }.andExpect { responseCode("117") }

        // Soft delete: the row is kept, marked DELETED.
        val status = jdbcClient.sql("SELECT categorystatus FROM public.ec_category WHERE categoryid = :id")
            .param("id", categoryId)
            .query(String::class.java)
            .single()
        assertEquals("DELETED", status.uppercase())
    }

    @Test
    fun `deleting a category that still has products returns 409`() {
        val categoryId = createCategory()
        // Product endpoints arrive in Step 3, so the product row is inserted directly.
        jdbcClient.sql(
            """
            INSERT INTO public.ec_product (productcategoryid, productcreatedbyadminid, productname, productsku, productprice)
            VALUES (:categoryId, :adminId, 'Test product', :sku, 1000)
            """.trimIndent(),
        )
            .param("categoryId", categoryId)
            .param("adminId", seedAdminId())
            .param("sku", "SKU-${UUID.randomUUID().toString().take(8)}")
            .update()

        mockMvc.delete("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value(containsString("Category still has products")) }
        }
        assertTrue(categoryId in adminCategoryIds())
    }

    @Test
    fun `an unknown category returns 404`() {
        mockMvc.get("/dev/admin/categories/999999") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { responseCode("25") }
    }

    @Test
    fun `customers and anonymous callers cannot manage categories`() {
        mockMvc.post("/dev/admin/categories") {
            header("Authorization", "Bearer ${newCustomerToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to uniqueName())
        }.andExpect { responseCode("107") }

        mockMvc.get("/dev/admin/categories").andExpect { responseCode("115") }

        // Only GET is public on /dev/categories.
        mockMvc.post("/dev/categories") {
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to uniqueName())
        }.andExpect { responseCode("115") }
    }

    // Acceptance check 1.2: customers cannot edit, deactivate or delete (not only create).
    @Test
    fun `customers cannot edit, deactivate or delete a category`() {
        val name = uniqueName()
        val categoryId = createCategory(name)
        val customerToken = newCustomerToken()

        mockMvc.patch("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $customerToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to uniqueName())
        }.andExpect { responseCode("107") }
        mockMvc.patch("/dev/admin/categories/$categoryId/status") {
            header("Authorization", "Bearer $customerToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to "INACTIVE")
        }.andExpect { responseCode("107") }
        mockMvc.delete("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $customerToken")
        }.andExpect { responseCode("107") }

        mockMvc.get("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            responseCode("00")
            jsonPath("$.data.name") { value(name) }
            jsonPath("$.data.status") { value("ACTIVE") }
        }
    }

    @Test
    fun `an admin whose password must still be changed is blocked`() {
        mockMvc.get("/dev/admin/categories") {
            header("Authorization", "Bearer ${loginSeedAdmin()}")
        }.andExpect { responseCode("107") }
    }

    @Test
    fun `create without a description stores none`() {
        val categoryId = createCategory(description = "   ")
        val result = mockMvc.get("/dev/admin/categories/$categoryId") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect { responseCode("00") }.andReturn()
        val body = readJson(result)
        assertNotNull(body["name"])
        assertNull(body["description"].takeUnless { it.isNull })
    }
}
