package com.rita.ecommerceplatform.repository.api

import io.github.oshai.kotlinlogging.KotlinLogging
import com.rita.ecommerceplatform.config.CloudinaryProps
import com.rita.ecommerceplatform.exception.ApiErrorException
import com.rita.ecommerceplatform.utility.ApiUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.stereotype.Repository
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * ImageStorage backed by Cloudinary's REST upload API (https://cloudinary.com/documentation/image_upload_api_reference).
 * Every call is signed: SHA-1 of the sorted parameters followed by the API secret. The secret itself is never
 * sent or logged. Failures (network, timeout, 4xx, 5xx, unreadable answer) are logged and become a 502.
 */
@Repository
class CloudinaryApi(
    private val apiUtil: ApiUtil,
    private val okHttpClient: OkHttpClient,
    private val cloudinaryProps: CloudinaryProps,
    private val jsonMapper: JsonMapper,
) : ImageStorage {

    private val cloudName = cloudinaryProps.cloudName.trim()
    private val apiKey = cloudinaryProps.apiKey.trim()
    private val apiSecret = cloudinaryProps.apiSecret.trim()
    private val isConfigured = cloudName.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()

    init {
        if (!isConfigured) logger.warn { "cloudinary.* is not set: product image uploads will be refused" }
    }

    override fun upload(content: ByteArray, contentType: String, folder: String): StoredImage {
        ensureConfigured()
        // A random name: the original file name never shows in the URL.
        val publicId = "$folder/${UUID.randomUUID()}"
        val what = "upload $publicId"
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        signed(mapOf("public_id" to publicId)).forEach { (name, value) -> form.addFormDataPart(name, value) }
        form.addFormDataPart("file", "image", content.toRequestBody(contentType.toMediaType()))
        val request = Request.Builder().url(endpoint("upload")).post(form.build()).build()

        // Not through ApiUtil, whose request body is a String: that would corrupt the image's bytes.
        val (status, body) = send(what) { okHttpClient.newCall(request).execute().use { it.code to it.body.string() } }
        if (status !in 200..299) {
            logger.error { "Cloudinary $what failed: HTTP $status ${errorOf(body)}" }
            throw ApiErrorException(STORAGE_ERROR)
        }
        val data = dataOf(body, what)
        return StoredImage(
            url = data.text("secure_url") ?: throw unreadable("upload $publicId"),
            storageKey = data.text("public_id") ?: throw unreadable("upload $publicId"),
        )
    }

    override fun delete(storageKey: String) {
        ensureConfigured()
        // invalidate: Cloudinary's CDN stops serving its cached copy too.
        val what = "destroy $storageKey"
        val form = signed(mapOf("public_id" to storageKey, "invalidate" to "true")).entries
            .joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val body = send(what) { apiUtil.post(hashMapOf(), form, endpoint("destroy"), "application/x-www-form-urlencoded") }
        val result = dataOf(body, what).text("result")
        // "not found" means it is already gone, which is what we wanted.
        if (result != "ok" && result != "not found") {
            logger.error { "Cloudinary destroy $storageKey answered result $result" }
            throw ApiErrorException(STORAGE_ERROR)
        }
    }

    /** The parameters plus timestamp, api_key and signature, as Cloudinary expects them. */
    internal fun signed(params: Map<String, String>): Map<String, String> {
        val withTimestamp = params + ("timestamp" to Instant.now().epochSecond.toString())
        return withTimestamp + mapOf("api_key" to apiKey, "signature" to signature(withTimestamp))
    }

    // https://cloudinary.com/documentation/authentication_signatures : "a=1&b=2" (sorted by name) + secret, SHA-1, hex.
    internal fun signature(params: Map<String, String>): String {
        val toSign = params.toSortedMap().entries.joinToString("&") { (name, value) -> "$name=$value" } + apiSecret
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(toSign.toByteArray(Charsets.UTF_8)))
    }

    private fun endpoint(action: String) = "${cloudinaryProps.baseUrl}/v1_1/$cloudName/image/$action"

    /** Network problems become a 502. */
    private fun <T> send(what: String, request: () -> T): T =
        try {
            request()
        } catch (ex: IOException) {
            logger.error(ex) { "Cloudinary $what failed: could not reach Cloudinary" }
            throw ApiErrorException(STORAGE_ERROR)
        }

    private fun dataOf(body: String, what: String): JsonNode {
        val json = parse(body, what)
        if (json == null || !json.isObject || json.has("error")) {
            logger.error { "Cloudinary $what failed: ${errorOf(body)}" }
            throw ApiErrorException(STORAGE_ERROR)
        }
        return json
    }

    // Cloudinary explains errors as {"error":{"message":...}}.
    private fun errorOf(body: String): String =
        runCatching { jsonMapper.readTree(body).get("error")?.text("message") }.getOrNull() ?: body.take(500)

    private fun parse(body: String, what: String): JsonNode? =
        try {
            jsonMapper.readTree(body)
        } catch (ex: JacksonException) {
            logger.error { "Cloudinary $what answered with a body that is not JSON: ${body.take(500)}" }
            null
        }

    private fun unreadable(what: String): ApiErrorException {
        logger.error { "Cloudinary $what answered without the expected fields" }
        return ApiErrorException(STORAGE_ERROR)
    }

    private fun ensureConfigured() {
        if (!isConfigured) throw ApiErrorException(NOT_CONFIGURED)
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

    companion object {
        const val STORAGE_ERROR = "The image storage could not be reached, please try again later"
        const val NOT_CONFIGURED = "Image storage is not configured"
    }
}
