package com.rita.ecommerceplatform.utility

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val logger = KotlinLogging.logger {}

/**
 * Thin wrapper around the shared [OkHttpClient]. The Spring bean `AppBeansConfig.apiUtil` injects
 * the application-wide client (connection pool, timeouts, redacted logging; see OkHttpConfig), so
 * we never build a new client on the hot path.
 */
class ApiUtil(
    private val httpClient: OkHttpClient,
) {

    /** Toggle logging of the request and response (sanitised). */
    var logging = true

    @Throws(IOException::class)
    fun post(
        headers: HashMap<String, String>,
        request: String,
        endpoint: String,
        mediaType: String = "application/json",
    ): String {
        val req = Request.Builder()
            .url(endpoint)
            .post(request.toRequestBody(mediaType.toMediaType()))
            .also { b -> headers.forEach { (k, v) -> b.addHeader(k, v) } }
            .build()

        if (logging) {
            logger.info { "--> POST ${redactUrl(endpoint)} headers=${sanitizeHeaders(headers)}" }
            logger.info { "request=${sanitizeData(request)}" }
        }

        val resp = try {
            httpClient.newCall(req).execute()
        } catch (e: Exception) {
            if (logging) {
                logger.error { "<-- POST ${redactUrl(endpoint)} FAILED: ${e.javaClass.simpleName}: ${e.message}" }
            }
            throw e
        }

        val body = resp.body.string()
        if (logging) {
            logger.info { "<-- POST ${redactUrl(endpoint)} status=${resp.code}" }
            logger.info { "response=${sanitizeData(body)}" }
        }
        return body
    }

    @Throws(IOException::class)
    fun get(
        headers: HashMap<String, String>,
        endpoint: String,
    ): String {
        val req = Request.Builder()
            .url(endpoint)
            .get()
            .also { b -> headers.forEach { (k, v) -> b.addHeader(k, v) } }
            .build()

        if (logging) {
            logger.info { "--> GET ${redactUrl(endpoint)} headers=${sanitizeHeaders(headers)}" }
        }

        val resp = try {
            httpClient.newCall(req).execute()
        } catch (e: Exception) {
            if (logging) {
                logger.error { "<-- GET ${redactUrl(endpoint)} FAILED: ${e.javaClass.simpleName}: ${e.message}" }
            }
            throw e
        }

        val body = resp.body.string()
        if (logging) {
            logger.info { "<-- GET ${redactUrl(endpoint)} status=${resp.code}" }
            logger.info { "response=${sanitizeData(body)}" }
        }
        return body
    }

    private fun sanitizeData(dataString: String): String =
        LogRedactor.body(dataString, SENSITIVE_FIELDS).take(2000)

    private fun sanitizeHeaders(headers: HashMap<String, String>): String {
        val sanitized = HashMap<String, String>(headers.size)
        headers.forEach { (key, value) ->
            val mask = SENSITIVE_HEADER_MARKERS.any { key.lowercase().contains(it) }
            sanitized[key] = if (mask) "******" else value
        }
        return sanitized.toString()
    }

    companion object {
        private val SENSITIVE_FIELDS = listOf(
            "adminPassword", "userPassword", "oldPassword", "otp", "pin",
            "oldPin", "newPin", "newPinConfirmation", "pinConfirmation",
            "customerPin", "customerOtp", "customerConfirmPassword",
            "customerPassword", "password", "customerBvn", "customerPhone",
            "customerPasswordConfirmation", "awsSecret", "token", "awsKey",
            "privileges", "accounts", "fcmToken", "customerPushId", "pushId",
            "validationToken", "messageBody",
            "authenticationCode",
            "authenticationKey", "secret", "clientid",

            // --- Financial & Balance fields ---
            "availableBalance", "ledgerBalance", "withdrawableBalance",
            "accountBalance", "balance", "balanceInNaira", "openingBalance",
            "withdrawableAmount", "maximumBalance", "maximumDeposit",
        )

        private val SENSITIVE_HEADER_MARKERS = listOf(
            "auth", "cookie", "authorization", "x-api-key", "x-auth-token", "x-access-token",
            "bearer", "token", "password", "secret", "key",
        )

        private val SENSITIVE_QUERY_PARAMS = setOf(
            "authtoken", "apikey", "token", "secret", "password", "pin", "otp",
        )

        private fun redactUrl(url: String): String {
            return try {
                // 1. Mask any UUIDs anywhere in the URL (BankOne auth tokens are typically UUIDs).
                val uuidRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                val safeUrl = url.replace(uuidRegex, "******")

                // 2. Then handle the standard query parameters (?key=value).
                val uri = java.net.URI(safeUrl)
                val query = uri.query ?: return safeUrl

                val redactedQuery = query.split("&").joinToString("&") { param ->
                    val key = param.substringBefore("=")
                    if (key.lowercase() in SENSITIVE_QUERY_PARAMS) "$key=******" else param
                }

                "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}${uri.path}?$redactedQuery"
            } catch (_: Exception) {
                url
            }
        }
    }
}
