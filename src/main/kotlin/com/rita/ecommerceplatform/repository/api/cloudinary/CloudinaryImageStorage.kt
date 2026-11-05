package com.rita.ecommerceplatform.repository.api.cloudinary

import com.rita.ecommerceplatform.exception.ApiErrorException
import com.rita.ecommerceplatform.repository.api.ImageStorage
import com.rita.ecommerceplatform.repository.api.StoredImage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * ImageStorage backed by Cloudinary's REST upload API (https://cloudinary.com/documentation/image_upload_api_reference).
 * Every call is signed: SHA-1 of the sorted parameters followed by the API secret. The secret itself is never
 * sent or logged. Failures (network, timeout, 4xx, 5xx, unreadable answer) are logged and become a 502.
 */
@Component
class CloudinaryImageStorage(
    @Qualifier("cloudinaryRestClient") private val restClient: RestClient,
    private val properties: CloudinaryProperties,
    private val jsonMapper: JsonMapper,
) : ImageStorage {

    private val log = LoggerFactory.getLogger(CloudinaryImageStorage::class.java)

    init {
        if (!properties.isConfigured) log.warn("CLOUDINARY_* is not set: product image uploads will answer 503")
    }

    override fun upload(content: ByteArray, contentType: String, folder: String): StoredImage {
        ensureConfigured()
        // A random name: the original file name never shows in the URL.
        val publicId = "$folder/${UUID.randomUUID()}"
        val signed = signed(mapOf("public_id" to publicId))
        val form = LinkedMultiValueMap<String, Any>()
        signed.forEach { (name, value) -> form.add(name, value) }
        form.add("file", filePart(content, contentType))

        val data = call("upload $publicId", "/v1_1/{cloud}/image/upload", form)
        return StoredImage(
            url = data.text("secure_url") ?: throw unreadable("upload $publicId"),
            storageKey = data.text("public_id") ?: throw unreadable("upload $publicId"),
        )
    }

    override fun delete(storageKey: String) {
        ensureConfigured()
        // invalidate: Cloudinary's CDN stops serving its cached copy too.
        val form = LinkedMultiValueMap<String, Any>()
        signed(mapOf("public_id" to storageKey, "invalidate" to "true")).forEach { (name, value) -> form.add(name, value) }
        val result = call("destroy $storageKey", "/v1_1/{cloud}/image/destroy", form).text("result")
        // "not found" means it is already gone, which is what we wanted.
        if (result != "ok" && result != "not found") {
            log.error("Cloudinary destroy {} answered result {}", storageKey, result)
            throw ApiErrorException(STORAGE_ERROR)
        }
    }

    /** The parameters plus timestamp, api_key and signature, as Cloudinary expects them. */
    internal fun signed(params: Map<String, String>): Map<String, String> {
        val withTimestamp = params + ("timestamp" to Instant.now().epochSecond.toString())
        return withTimestamp + mapOf("api_key" to properties.apiKey, "signature" to signature(withTimestamp))
    }

    // https://cloudinary.com/documentation/authentication_signatures : "a=1&b=2" (sorted by name) + secret, SHA-1, hex.
    internal fun signature(params: Map<String, String>): String {
        val toSign = params.toSortedMap().entries.joinToString("&") { (name, value) -> "$name=$value" } + properties.apiSecret
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(toSign.toByteArray(Charsets.UTF_8)))
    }

    private fun filePart(content: ByteArray, contentType: String): HttpEntity<ByteArrayResource> {
        val resource = object : ByteArrayResource(content) {
            override fun getFilename() = "image"
        }
        val headers = HttpHeaders().apply { this.contentType = MediaType.parseMediaType(contentType) }
        return HttpEntity(resource, headers)
    }

    private fun call(what: String, path: String, form: LinkedMultiValueMap<String, Any>): JsonNode {
        val (status, body) = try {
            restClient.post().uri(path, properties.cloudName)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .exchange { _, response -> response.statusCode to response.body.readAllBytes().toString(Charsets.UTF_8) }
                ?: throw unreadable(what)
        } catch (ex: RestClientException) {
            log.error("Cloudinary {} failed: could not reach Cloudinary", what, ex)
            throw ApiErrorException(STORAGE_ERROR)
        }
        val json = parse(body, what)
        if (!status.is2xxSuccessful || json == null || !json.isObject || json.has("error")) {
            // Cloudinary explains errors as {"error":{"message":...}}.
            log.error("Cloudinary {} failed: HTTP {} {}", what, status.value(), json?.get("error")?.text("message") ?: body.take(500))
            throw ApiErrorException(STORAGE_ERROR)
        }
        return json
    }

    private fun parse(body: String, what: String): JsonNode? =
        try {
            jsonMapper.readTree(body)
        } catch (ex: JacksonException) {
            log.error("Cloudinary {} answered with a body that is not JSON: {}", what, body.take(500))
            null
        }

    private fun unreadable(what: String): ApiErrorException {
        log.error("Cloudinary {} answered without the expected fields", what)
        return ApiErrorException(STORAGE_ERROR)
    }

    private fun ensureConfigured() {
        if (!properties.isConfigured) throw ApiErrorException(NOT_CONFIGURED)
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

    companion object {
        const val STORAGE_ERROR = "The image storage could not be reached, please try again later"
        const val NOT_CONFIGURED = "Image storage is not configured"
    }
}
