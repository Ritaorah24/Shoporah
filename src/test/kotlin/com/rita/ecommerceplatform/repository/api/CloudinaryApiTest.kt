package com.rita.ecommerceplatform.repository.api

import com.rita.ecommerceplatform.config.CloudinaryProps
import com.rita.ecommerceplatform.exception.ApiErrorException
import com.rita.ecommerceplatform.support.MockHttpServer
import com.rita.ecommerceplatform.utility.ApiUtil
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CloudinaryApi against a mocked Cloudinary (checkpoint 2.2, Step 8): the signed requests it sends and how it
 * reads the answers. No Spring context and no network; the answers are shaped like Cloudinary's documented ones.
 */
class CloudinaryApiTest {

    private val properties = CloudinaryProps(baseUrl = "https://api.cloudinary.com", cloudName = "demo", apiKey = "key123", apiSecret = "secret456")
    private val server = MockHttpServer()

    private fun api(props: CloudinaryProps) = CloudinaryApi(ApiUtil(server.client), server.client, props, JsonMapper.builder().build())

    private val storage = api(properties)

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)

    // The form fields of a multipart body: name="x" ... blank line, then the value.
    private fun fields(request: MockHttpServer.Recorded): Map<String, String> =
        Regex("name=\"(\\w+)\"[^\\r\\n]*\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n([^\\r\\n]*)")
            .findAll(request.bodyAsString)
            .associate { it.groupValues[1] to it.groupValues[2] }

    // The fields of a URL-encoded form body.
    private fun formFields(request: MockHttpServer.Recorded): Map<String, String> =
        request.bodyAsString.split("&").associate { pair ->
            pair.substringBefore("=") to URLDecoder.decode(pair.substringAfter("="), Charsets.UTF_8)
        }

    @Test
    fun `the signature is Cloudinary's documented example`() {
        // https://cloudinary.com/documentation/authentication_signatures, with the example secret "abcd".
        val documented = api(properties.copy(apiSecret = "abcd"))
        assertEquals(
            "bfd09f95f331f558cbd1320e67aa8d488770583e",
            documented.signature(
                mapOf("timestamp" to "1315060510", "public_id" to "sample_image", "eager" to "w_400,h_300,c_pad|w_260,h_200,c_crop"),
            ),
        )
    }

    @Test
    fun `upload sends a signed request with a random name in the folder, and returns the HTTPS URL and key`() {
        var sent: Map<String, String> = emptyMap()
        var body = ""
        server.expect("https://api.cloudinary.com/v1_1/demo/image/upload")
            .andExpectMethod("POST")
            .andExpect { request ->
                body = request.bodyAsString
                sent = fields(request)
            }
            .andRespond(
                MockHttpServer.Answer(body = """{"public_id":"shoporah/products/5/abc","secure_url":"https://res.cloudinary.com/demo/image/upload/v1/shoporah/products/5/abc.jpg","format":"jpg","bytes":5}"""),
            )

        val stored = storage.upload(jpeg, "image/jpeg", "shoporah/products/5")

        assertEquals("https://res.cloudinary.com/demo/image/upload/v1/shoporah/products/5/abc.jpg", stored.url)
        assertEquals("shoporah/products/5/abc", stored.storageKey)
        assertTrue(sent.getValue("public_id").matches(Regex("shoporah/products/5/[0-9a-f-]{36}")), sent.toString())
        assertEquals("key123", sent["api_key"])
        val signedPart = mapOf("public_id" to sent.getValue("public_id"), "timestamp" to sent.getValue("timestamp"))
        assertEquals(storage.signature(signedPart), sent["signature"])
        assertTrue("name=\"file\"" in body && "Content-Type: image/jpeg" in body, body)
        assertFalse("secret456" in body, "the API secret must never be sent")
        server.verify()
    }

    @Test
    fun `delete sends a signed destroy and accepts ok or not found`() {
        listOf("ok", "not found").forEach { result ->
            server.reset()
            var sent: Map<String, String> = emptyMap()
            server.expect("https://api.cloudinary.com/v1_1/demo/image/destroy")
                .andExpectMethod("POST")
                .andExpect { sent = formFields(it) }
                .andRespond(MockHttpServer.Answer(body = """{"result":"$result"}"""))

            storage.delete("shoporah/products/5/abc")

            assertEquals("shoporah/products/5/abc", sent["public_id"])
            assertEquals("true", sent["invalidate"])
            val signedPart = mapOf("public_id" to "shoporah/products/5/abc", "invalidate" to "true", "timestamp" to sent.getValue("timestamp"))
            assertEquals(storage.signature(signedPart), sent["signature"])
        }
    }

    @Test
    fun `Cloudinary errors, bad answers and network failures become a 502`() {
        listOf(
            MockHttpServer.Answer(status = HttpStatus.UNAUTHORIZED.value(), body = """{"error":{"message":"Invalid Signature"}}"""),
            MockHttpServer.Answer(status = HttpStatus.BAD_REQUEST.value(), body = """{"error":{"message":"Invalid image file"}}"""),
            MockHttpServer.Answer(status = HttpStatus.INTERNAL_SERVER_ERROR.value(), body = "oops"),
            MockHttpServer.Answer(body = """{"public_id":"x"}"""),
            MockHttpServer.Answer(body = "<html>gateway</html>", contentType = "text/html"),
            MockHttpServer.Answer(failure = IOException("Connection reset")),
        ).forEach { response ->
            server.reset()
            server.expect("https://api.cloudinary.com/v1_1/demo/image/upload").andRespond(response)
            val ex = assertFailsWith<ApiErrorException> { storage.upload(jpeg, "image/jpeg", "shoporah/products/5") }
            assertEquals(CloudinaryApi.STORAGE_ERROR, ex.message)
        }
        server.reset()
        server.expect("https://api.cloudinary.com/v1_1/demo/image/destroy")
            .andRespond(MockHttpServer.Answer(body = """{"result":"error"}"""))
        assertFailsWith<ApiErrorException> { storage.delete("shoporah/products/5/abc") }
    }

    @Test
    fun `without the Cloudinary settings nothing is sent and the answer is 503`() {
        val unconfigured = api(CloudinaryProps(baseUrl = "https://api.cloudinary.com"))
        val ex = assertFailsWith<ApiErrorException> { unconfigured.upload(jpeg, "image/jpeg", "shoporah/products/5") }
        assertEquals("Image storage is not configured", ex.message)
        assertFailsWith<ApiErrorException> { unconfigured.delete("x") }
        server.verify()
    }
}
