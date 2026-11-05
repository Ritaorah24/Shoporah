package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.service.TestImages
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Product images in the product and cart responses, and editing them (checkpoint 2.2, Step 8b). Cloudinary is the fake. */
class ProductImageDisplayTest : IntegrationTestBase() {

    private lateinit var adminToken: String

    // Each test lists its own category, so other tests' products never appear in the results.
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        categoryId = readJson(
            mockMvc.post("/api/admin/categories") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = json("name" to "Cat-${UUID.randomUUID().toString().take(8)}")
            }.andExpect { responseCode("00") }.andReturn(),
        )["categoryId"].asInt()
    }

    private fun createProduct(): Long = readJson(
        mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to "Shown phone",
                "sku" to "sku-${UUID.randomUUID().toString().take(8)}",
                "categoryId" to categoryId,
                "price" to 1000,
                "stockQuantity" to 10,
            )
        }.andExpect { responseCode("00") }.andReturn(),
    )["productId"].asLong()

    /** Uploads an image and returns (imageId, url). */
    private fun upload(productId: Long, altText: String? = null, content: ByteArray = TestImages.JPEG): Pair<Long, String> {
        val image = readJson(
            mockMvc.multipart("/api/admin/products/$productId/images") {
                header("Authorization", "Bearer $adminToken")
                file(MockMultipartFile("file", "photo", "image/jpeg", content))
                altText?.let { param("altText", it) }
            }.andExpect { responseCode("00") }.andReturn(),
        )
        return image["imageId"].asLong() to image["url"].asString()
    }

    private fun patchImage(productId: Long, imageId: Long, vararg fields: Pair<String, Any?>, withToken: String = adminToken): ResultActionsDsl =
        mockMvc.patch("/api/admin/products/$productId/images/$imageId") {
            header("Authorization", "Bearer $withToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(*fields)
        }

    private fun publicProduct(productId: Long): JsonNode = readJson(
        mockMvc.get("/api/products/$productId").andExpect { responseCode("00") }.andReturn(),
    )

    private fun adminProduct(productId: Long): JsonNode = readJson(
        mockMvc.get("/api/admin/products/$productId") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("00") }.andReturn(),
    )

    private fun publicList(): Map<Long, JsonNode> = readJson(
        mockMvc.get("/api/products") { param("categoryId", categoryId.toString()) }.andExpect { responseCode("00") }.andReturn(),
    )["content"].toList().associateBy { it["productId"].asLong() }

    private fun imageIds(images: JsonNode): List<Long> = images.toList().map { it["imageId"].asLong() }

    // A null field, whether it is written as null or left out.
    private fun JsonNode.isNullAt(field: String): Boolean = get(field)?.isNull ?: true

    @Test
    fun `a product without images shows no image, publicly and to admins`() {
        val productId = createProduct()

        listOf(publicProduct(productId), adminProduct(productId), publicList().getValue(productId)).forEach { product ->
            assertTrue(product.isNullAt("primaryImageUrl"), product.toString())
            assertEquals(0, product["images"].size())
        }
    }

    @Test
    fun `the detail lists the primary image first, then in order, and the public sees no ids or keys`() {
        val productId = createProduct()
        val (_, frontUrl) = upload(productId, altText = "Front")
        val (_, backUrl) = upload(productId, altText = "Back")

        val product = publicProduct(productId)
        assertEquals(frontUrl, product["primaryImageUrl"].asString())
        assertEquals(listOf(frontUrl, backUrl), product["images"].toList().map { it["url"].asString() })
        assertEquals(listOf("Front", "Back"), product["images"].toList().map { it["altText"].asString() })
        assertEquals(listOf(true, false), product["images"].toList().map { it["primary"].asBoolean() })
        val image = product["images"][0]
        assertEquals(3, image.size(), image.toString())
        assertFalse(image.has("imageId") || image.has("storageKey"), image.toString())

        val admin = adminProduct(productId)
        assertEquals(frontUrl, admin["primaryImageUrl"].asString())
        assertEquals(listOf(0, 1), admin["images"].toList().map { it["sortOrder"].asInt() })
        assertFalse(admin.toString().contains("storageKey"))
    }

    @Test
    fun `each product in a list has its own images, from one page`() {
        val withTwo = createProduct()
        val (_, firstUrl) = upload(withTwo)
        upload(withTwo)
        val withOne = createProduct()
        val (_, onlyUrl) = upload(withOne)
        val withNone = createProduct()

        val list = publicList()
        assertEquals(firstUrl, list.getValue(withTwo)["primaryImageUrl"].asString())
        assertEquals(2, list.getValue(withTwo)["images"].size())
        assertEquals(onlyUrl, list.getValue(withOne)["primaryImageUrl"].asString())
        assertEquals(1, list.getValue(withOne)["images"].size())
        assertEquals(0, list.getValue(withNone)["images"].size())

        val adminList = readJson(
            mockMvc.get("/api/admin/products") {
                header("Authorization", "Bearer $adminToken")
                param("categoryId", categoryId.toString())
            }.andExpect { responseCode("00") }.andReturn(),
        )["content"].toList().associateBy { it["productId"].asLong() }
        assertEquals(onlyUrl, adminList.getValue(withOne)["primaryImageUrl"].asString())
    }

    @Test
    fun `making another image primary moves the flag, and lists show the new thumbnail`() {
        val productId = createProduct()
        val (first, _) = upload(productId)
        val (second, secondUrl) = upload(productId)

        patchImage(productId, second, "primary" to true).andExpect {
            responseCode("00")
            jsonPath("$.data[0].imageId") { value(second) }
            jsonPath("$.data[0].primary") { value(true) }
            jsonPath("$.data[1].imageId") { value(first) }
            jsonPath("$.data[1].primary") { value(false) }
        }
        assertEquals(secondUrl, publicList().getValue(productId)["primaryImageUrl"].asString())
        // Making the primary image primary again changes nothing.
        patchImage(productId, second, "primary" to true).andExpect { responseCode("00") }
        assertEquals(listOf(second, first), imageIds(adminProduct(productId)["images"]))
    }

    @Test
    fun `two primary changes at the same time still leave exactly one primary image`() {
        val productId = createProduct()
        upload(productId)
        val (second, _) = upload(productId)
        val (third, _) = upload(productId)

        val pool = Executors.newFixedThreadPool(2)
        val statuses = try {
            pool.invokeAll(listOf(second, third).map { id ->
                Callable { patchImage(productId, id, "primary" to true).andReturn().responseCodeOf() }
            }).map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(listOf("00", "00"), statuses)
        val primaries = adminProduct(productId)["images"].toList().filter { it["primary"].asBoolean() }
        assertEquals(1, primaries.size)
        assertTrue(primaries.single()["imageId"].asLong() in setOf(second, third))
    }

    @Test
    fun `alt text and sort order can be changed, and bad edits are refused`() {
        val productId = createProduct()
        val (first, _) = upload(productId, altText = "Front")
        val (second, _) = upload(productId)
        val (third, _) = upload(productId)

        // Third moves before second (primary stays first whatever its sortOrder).
        patchImage(productId, third, "sortOrder" to 0, "altText" to "  Side view  ").andExpect {
            responseCode("00")
            jsonPath("$.data[1].imageId") { value(third) }
            jsonPath("$.data[1].altText") { value("Side view") }
        }
        assertEquals(listOf(first, third, second), imageIds(adminProduct(productId)["images"]))

        patchImage(productId, first, "altText" to "").andExpect {
            responseCode("00")
            jsonPath("$.data[0].altText") { doesNotExist() }
        }
        assertTrue(publicProduct(productId)["images"][0].isNullAt("altText"))

        patchImage(productId, second, "primary" to false).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Choose another image as primary instead") }
        }
        patchImage(productId, second).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Nothing to update") }
        }
        patchImage(productId, second, "altText" to "x".repeat(201)).andExpect { responseCode("106") }
        patchImage(productId, second, "sortOrder" to 1000).andExpect { responseCode("106") }
        patchImage(productId, second, "sortOrder" to -1).andExpect { responseCode("106") }
    }

    @Test
    fun `other products' images, deleted products and customers are refused`() {
        val productId = createProduct()
        val (imageId, _) = upload(productId)
        val other = createProduct()

        patchImage(other, imageId, "altText" to "x").andExpect { responseCode("25") }
        patchImage(productId, imageId, "altText" to "x", withToken = newCustomerToken()).andExpect { responseCode("107") }

        mockMvc.delete("/api/admin/products/$productId") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("00") }
        patchImage(productId, imageId, "altText" to "x").andExpect { responseCode("25") }
    }

    @Test
    fun `cart items show their product's primary image`() {
        val withImage = createProduct()
        val (_, url) = upload(withImage)
        val withoutImage = createProduct()
        val token = newCustomerToken()
        listOf(withImage, withoutImage).forEach { productId ->
            mockMvc.post("/api/customer/cart/items") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = json("productId" to productId, "quantity" to 1)
            }.andExpect { responseCode("00") }
        }

        val items = readJson(
            mockMvc.get("/api/customer/cart") { header("Authorization", "Bearer $token") }.andExpect { responseCode("00") }.andReturn(),
        )["items"].toList().associateBy { it["productId"].asLong() }
        assertEquals(url, items.getValue(withImage)["imageUrl"].asString())
        assertTrue(items.getValue(withoutImage).isNullAt("imageUrl"))
    }
}
