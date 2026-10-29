package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.service.TestImages
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uploading and removing product images (checkpoint 2.2, Step 8a). Cloudinary is the fake. */
class ProductImageTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        categoryId = readJson(
            mockMvc.post("/api/admin/categories") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = json("name" to "Cat-${UUID.randomUUID().toString().take(8)}")
            }.andExpect { status { isCreated() } }.andReturn(),
        )["categoryId"].asInt()
    }

    private fun createProduct(): Long = readJson(
        mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to "Image phone",
                "sku" to "sku-${UUID.randomUUID().toString().take(8)}",
                "categoryId" to categoryId,
                "price" to 1000,
            )
        }.andExpect { status { isCreated() } }.andReturn(),
    )["productId"].asLong()

    private fun upload(
        productId: Long,
        content: ByteArray = TestImages.JPEG,
        fileName: String = "photo.jpg",
        contentType: String = "image/jpeg",
        altText: String? = null,
        withToken: String = adminToken,
    ): ResultActionsDsl =
        mockMvc.multipart("/api/admin/products/$productId/images") {
            header("Authorization", "Bearer $withToken")
            file(MockMultipartFile("file", fileName, contentType, content))
            altText?.let { param("altText", it) }
        }

    private fun uploadId(productId: Long, content: ByteArray = TestImages.JPEG): Long =
        readJson(upload(productId, content).andExpect { status { isCreated() } }.andReturn())["imageId"].asLong()

    private fun deleteImage(productId: Long, imageId: Long): ResultActionsDsl =
        mockMvc.delete("/api/admin/products/$productId/images/$imageId") { header("Authorization", "Bearer $adminToken") }

    private fun folder(productId: Long) = "shoporah/products/$productId"

    private fun imageRows(productId: Long): List<Triple<Long, Boolean, String>> =
        jdbcClient.sql(
            """
            SELECT productimageid, productimageisprimary, productimagestoragekey FROM public.ec_product_image
            WHERE productimageproductid = :id ORDER BY productimagesortorder, productimageid
            """
        ).param("id", productId).query { rs, _ -> Triple(rs.getLong(1), rs.getBoolean(2), rs.getString(3)) }.list()

    @Test
    fun `JPEG, PNG and WebP images are stored, and the first one is primary`() {
        val productId = createProduct()

        upload(productId, altText = "  Front view  ").andExpect {
            status { isCreated() }
            jsonPath("$.productId") { value(productId) }
            jsonPath("$.primary") { value(true) }
            jsonPath("$.sortOrder") { value(0) }
            jsonPath("$.altText") { value("Front view") }
            jsonPath("$.url") { value(startsWith("https://images.example.test/${folder(productId)}/")) }
            jsonPath("$.storageKey") { doesNotExist() }
        }
        upload(productId, TestImages.PNG, "back.png", "image/png").andExpect {
            status { isCreated() }
            jsonPath("$.primary") { value(false) }
            jsonPath("$.sortOrder") { value(1) }
        }
        // The type comes from the bytes: a WebP sent with a wrong name and Content-Type is still a WebP.
        upload(productId, TestImages.WEBP, "side.bin", "application/octet-stream").andExpect {
            status { isCreated() }
            jsonPath("$.sortOrder") { value(2) }
        }

        val rows = imageRows(productId)
        assertEquals(listOf(true, false, false), rows.map { it.second })
        assertEquals(3, imageStorage.countIn(folder(productId)))
        rows.forEach { assertTrue(imageStorage.contains(it.third)) }
    }

    @Test
    fun `files that are not JPEG, PNG or WebP are refused, whatever they are called, and nothing is stored`() {
        val productId = createProduct()
        listOf(
            Triple("just text".toByteArray(), "photo.jpg", "image/jpeg"),
            Triple(TestImages.SVG, "logo.svg", "image/svg+xml"),
            Triple(TestImages.GIF, "anim.gif", "image/gif"),
        ).forEach { (content, name, type) ->
            upload(productId, content, name, type).andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("Only JPEG, PNG or WebP images are allowed") }
            }
        }
        upload(productId, ByteArray(0)).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("The image file is empty") }
        }
        upload(productId, altText = "x".repeat(201)).andExpect { status { isBadRequest() } }
        mockMvc.multipart("/api/admin/products/$productId/images") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("The 'file' file is required") }
            }

        assertEquals(0, imageStorage.countIn(folder(productId)))
        assertTrue(imageRows(productId).isEmpty())
    }

    @Test
    fun `a file over 5 MB is 413`() {
        val productId = createProduct()
        val tooBig = TestImages.JPEG + ByteArray(5 * 1024 * 1024)

        upload(productId, tooBig).andExpect {
            status { isPayloadTooLarge() }
            jsonPath("$.message") { value("The image must be 5 MB or smaller") }
        }
        assertEquals(0, imageStorage.countIn(folder(productId)))
    }

    @Test
    fun `a product has at most 8 images, also when uploads arrive at the same time`() {
        val productId = createProduct()
        repeat(7) { uploadId(productId) }

        val pool = Executors.newFixedThreadPool(2)
        val statuses = try {
            pool.invokeAll(List(2) { Callable { upload(productId).andReturn().response.status } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(listOf(201, 409), statuses.sorted())

        upload(productId).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("A product can have at most 8 images") }
        }
        assertEquals(8, imageRows(productId).size)
        // A refused upload leaves no file behind, even one that was stored before the limit was found.
        assertEquals(8, imageStorage.countIn(folder(productId)))
        assertEquals(1, imageRows(productId).count { it.second })
    }

    @Test
    fun `when the storage fails nothing is saved, and when saving fails the stored file is removed`() {
        val productId = createProduct()
        imageStorage.down = true
        try {
            upload(productId).andExpect { status { isBadGateway() } }
        } finally {
            imageStorage.down = false
        }
        assertTrue(imageRows(productId).isEmpty())

        Mockito.doThrow(IllegalStateException("Simulated failure while saving the image"))
            .`when`(productImageRepositorySpy).nextSortOrder(anyLong())
        try {
            upload(productId).andExpect { status { isInternalServerError() } }
        } finally {
            Mockito.reset(productImageRepositorySpy)
        }
        assertTrue(imageRows(productId).isEmpty())
        assertEquals(0, imageStorage.countIn(folder(productId)))

        imageStorage.notConfigured = true
        try {
            upload(productId).andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.message") { value("Image storage is not configured") }
            }
        } finally {
            imageStorage.notConfigured = false
        }
    }

    @Test
    fun `deleting the primary image makes the next one primary and removes the stored file`() {
        val productId = createProduct()
        val first = uploadId(productId)
        val second = uploadId(productId, TestImages.PNG)
        val third = uploadId(productId, TestImages.WEBP)
        val firstKey = imageRows(productId).first { it.first == first }.third

        deleteImage(productId, first).andExpect { status { isNoContent() } }

        assertEquals(listOf(second to true, third to false), imageRows(productId).map { it.first to it.second })
        assertFalse(imageStorage.contains(firstKey))

        // A storage failure on delete is only logged: the image is gone from the product anyway.
        imageStorage.deleteFails = true
        try {
            deleteImage(productId, third).andExpect { status { isNoContent() } }
        } finally {
            imageStorage.deleteFails = false
        }
        assertEquals(listOf(second), imageRows(productId).map { it.first })
        deleteImage(productId, third).andExpect { status { isNotFound() } }
    }

    @Test
    fun `unknown or deleted products and other products' images are 404, and customers are refused`() {
        val productId = createProduct()
        val imageId = uploadId(productId)
        val other = createProduct()

        deleteImage(other, imageId).andExpect { status { isNotFound() } }
        upload(999_999_999).andExpect { status { isNotFound() } }

        mockMvc.delete("/api/admin/products/$productId") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isNoContent() } }
        upload(productId).andExpect { status { isNotFound() } }
        deleteImage(productId, imageId).andExpect { status { isNotFound() } }
        assertEquals(0, imageStorage.countIn(folder(other)))

        upload(other, withToken = newCustomerToken()).andExpect { status { isForbidden() } }
    }
}
