package com.rita.ecommerceplatform.repository.api.cloudinary

import com.rita.ecommerceplatform.exception.BadGatewayException
import com.rita.ecommerceplatform.exception.ServiceUnavailableException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CloudinaryImageStorage against a mocked Cloudinary (checkpoint 2.2, Step 8): the signed requests it sends and how it
 * reads the answers. No Spring context and no network; the answers are shaped like Cloudinary's documented ones.
 */
class CloudinaryImageStorageTest {

    private val properties = CloudinaryProperties(cloudName = "demo", apiKey = "key123", apiSecret = "secret456")
    private val builder = RestClient.builder().baseUrl("https://api.cloudinary.com")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val storage = CloudinaryImageStorage(builder.build(), properties, JsonMapper.builder().build())

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)

    // The form fields of a multipart body: name="x" ... blank line, then the value.
    private fun fields(request: MockClientHttpRequest): Map<String, String> =
        Regex("name=\"(\\w+)\"[^\\r\\n]*\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n([^\\r\\n]*)")
            .findAll(request.bodyAsString)
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `the signature is Cloudinary's documented example`() {
        // https://cloudinary.com/documentation/authentication_signatures, with the example secret "abcd".
        val documented = CloudinaryImageStorage(builder.build(), properties.copy(apiSecret = "abcd"), JsonMapper.builder().build())
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
        server.expect(requestTo("https://api.cloudinary.com/v1_1/demo/image/upload"))
            .andExpect(method(HttpMethod.POST))
            .andExpect { request ->
                val mock = request as MockClientHttpRequest
                body = mock.bodyAsString
                sent = fields(mock)
            }
            .andRespond(
                withSuccess(
                    """{"public_id":"shoporah/products/5/abc","secure_url":"https://res.cloudinary.com/demo/image/upload/v1/shoporah/products/5/abc.jpg","format":"jpg","bytes":5}""",
                    MediaType.APPLICATION_JSON,
                ),
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
            server.expect(requestTo("https://api.cloudinary.com/v1_1/demo/image/destroy"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { sent = fields(it as MockClientHttpRequest) }
                .andRespond(withSuccess("""{"result":"$result"}""", MediaType.APPLICATION_JSON))

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
            withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body("""{"error":{"message":"Invalid Signature"}}"""),
            withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""{"error":{"message":"Invalid image file"}}"""),
            withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("oops"),
            withSuccess("""{"public_id":"x"}""", MediaType.APPLICATION_JSON),
            withSuccess("<html>gateway</html>", MediaType.TEXT_HTML),
            withException(IOException("Connection reset")),
        ).forEach { response ->
            server.reset()
            server.expect(requestTo("https://api.cloudinary.com/v1_1/demo/image/upload")).andRespond(response)
            val ex = assertFailsWith<BadGatewayException> { storage.upload(jpeg, "image/jpeg", "shoporah/products/5") }
            assertEquals(CloudinaryImageStorage.STORAGE_ERROR, ex.message)
        }
        server.reset()
        server.expect(requestTo("https://api.cloudinary.com/v1_1/demo/image/destroy"))
            .andRespond(withSuccess("""{"result":"error"}""", MediaType.APPLICATION_JSON))
        assertFailsWith<BadGatewayException> { storage.delete("shoporah/products/5/abc") }
    }

    @Test
    fun `without the Cloudinary settings nothing is sent and the answer is 503`() {
        val unconfigured = CloudinaryImageStorage(builder.build(), CloudinaryProperties("", "", ""), JsonMapper.builder().build())
        val ex = assertFailsWith<ServiceUnavailableException> { unconfigured.upload(jpeg, "image/jpeg", "shoporah/products/5") }
        assertEquals("Image storage is not configured", ex.message)
        assertFailsWith<ServiceUnavailableException> { unconfigured.delete("x") }
        server.verify()
    }
}
