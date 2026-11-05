package com.rita.ecommerceplatform.repository.api

import io.github.oshai.kotlinlogging.KotlinLogging
import com.rita.ecommerceplatform.config.PaystackProps
import com.rita.ecommerceplatform.exception.ApiErrorException
import com.rita.ecommerceplatform.utility.ApiUtil
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.stereotype.Repository
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * PaymentGateway backed by Paystack's REST API (https://paystack.com/docs/api/transaction/).
 * Paystack answers { "status": true|false, "message": ..., "data": {...} }.
 * Every failure (network, timeout, 4xx, 5xx, unreadable body) is logged and becomes a code 22;
 * the secret key itself is never logged.
 */
@Repository
class PaystackApi(
    okHttpClient: OkHttpClient,
    private val paystackProps: PaystackProps,
    private val jsonMapper: JsonMapper,
) : PaymentGateway {

    // The shared client's pool, with this integration's own limits. PaymentService.STALE_START_SECONDS and
    // RefundService.UNCONFIRMED_GRACE_SECONDS rely on a call ending within 15 s.
    private val httpClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // A refund is never re-sent by the client: only findRefunds decides whether one was created. Keeping no idle
    // connections means it always starts on a fresh one, so a connection the server closed cannot fail it.
    private val refundClient = httpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    // Bodies carry customer details, so only failures are logged (below).
    private val apiUtil = ApiUtil(httpClient).apply { logging = false }

    init {
        require(paystackProps.secretKey.isNotBlank()) { "paystack.secret-key must be set" }
    }

    override fun initialize(request: GatewayInitRequest): GatewayInitResult {
        val body = buildMap {
            put("reference", request.reference)
            put("email", request.email)
            put("amount", request.amountKobo)
            put("currency", request.currency)
            request.callbackUrl?.takeIf { it.isNotBlank() }?.let { put("callback_url", it) }
        }
        val responseBody = send("initialize ${request.reference}") {
            apiUtil.post(authHeaders(), jsonMapper.writeValueAsString(body), url("transaction/initialize"))
        }
        val data = dataOf(responseBody, "initialize ${request.reference}")
        return GatewayInitResult(
            reference = data.text("reference") ?: request.reference,
            authorizationUrl = data.text("authorization_url") ?: throw unreadable("initialize ${request.reference}"),
            accessCode = data.text("access_code") ?: throw unreadable("initialize ${request.reference}"),
        )
    }

    override fun verify(reference: String): GatewayVerifyResult? {
        val responseBody = send("verify $reference") {
            apiUtil.get(authHeaders(), url("transaction/verify", reference))
        }
        if (isUnknownReference(responseBody)) return null
        val data = dataOf(responseBody, "verify $reference")
        return try {
            GatewayVerifyResult(
                reference = data.text("reference") ?: reference,
                status = statusOf(data.text("status")),
                amountKobo = data.get("amount")?.asLong() ?: throw unreadable("verify $reference"),
                currency = data.text("currency") ?: throw unreadable("verify $reference"),
                transactionId = data.text("id"),
                channel = data.text("channel"),
                gatewayResponse = data.text("gateway_response"),
                paidAt = data.text("paid_at")?.let(OffsetDateTime::parse),
                rawResponse = responseBody,
            )
        } catch (ex: java.time.format.DateTimeParseException) {
            throw unreadable("verify $reference", ex)
        }
    }

    // https://paystack.com/docs/api/refund/ : `transaction` takes the payment's reference.
    // Not through ApiUtil, which returns only the body: a clear refusal is told from "we do not know whether it was
    // created" by the HTTP status, and taking one for the other could send a refund twice.
    override fun refund(request: GatewayRefundRequest): GatewayRefundStart {
        val what = "refund ${request.transactionReference}"
        val body = buildMap {
            put("transaction", request.transactionReference)
            put("amount", request.amountKobo)
            put("currency", request.currency)
            request.merchantNote?.takeIf { it.isNotBlank() }?.let { put("merchant_note", it) }
        }
        val httpRequest = Request.Builder()
            .url(url("refund"))
            .header("Authorization", "Bearer ${paystackProps.secretKey}")
            .post(jsonMapper.writeValueAsString(body).toRequestBody(JSON))
            .build()
        val (status, responseBody) = send(what) {
            refundClient.newCall(httpRequest).execute().use { it.code to it.body.string() }
        }
        rejectionOf(status, responseBody)?.let { message ->
            logger.warn { "Paystack $what was refused: HTTP $status $message" }
            return GatewayRefundStart.Rejected(message)
        }
        if (status !in 200..299) {
            logger.error { "Paystack $what failed: HTTP $status $responseBody" }
            throw ApiErrorException(PROVIDER_ERROR)
        }
        return GatewayRefundStart.Accepted(refundOf(dataOf(responseBody, what), responseBody, what))
    }

    override fun fetchRefund(providerRefundId: String): GatewayRefund? {
        val what = "fetch refund $providerRefundId"
        val responseBody = send(what) { apiUtil.get(authHeaders(), url("refund", providerRefundId)) }
        if (isUnknownReference(responseBody)) return null
        return refundOf(dataOf(responseBody, what), responseBody, what)
    }

    // Paystack filters the list by transaction id; the reference check below keeps only this payment's
    // refunds even if it did not (e.g. no id known), so another payment's refund is never taken for ours.
    override fun findRefunds(transactionId: String?, transactionReference: String): List<GatewayRefund> {
        val what = "list refunds of $transactionReference"
        val endpoint = url("refund").toHttpUrl().newBuilder()
            .addQueryParameter("perPage", LIST_PAGE_SIZE.toString())
            .apply { transactionId?.let { addQueryParameter("transaction", it) } }
            .build().toString()
        val responseBody = send(what) { apiUtil.get(authHeaders(), endpoint) }
        val data = dataOf(responseBody, what, expectArray = true)
        return (0 until data.size())
            .map { refundOf(data.get(it), data.get(it).toString(), what) }
            .filter { it.transactionReference.equals(transactionReference, ignoreCase = true) }
    }

    // A refund as Paystack describes it. On create, `transaction` is an object; on fetch and list it is the
    // transaction id, with the reference in `transaction_reference`.
    private fun refundOf(data: JsonNode, raw: String, what: String): GatewayRefund {
        val providerStatus = data.text("status")
        val status = refundStatusOf(providerStatus)
        return GatewayRefund(
            refundId = data.text("id") ?: throw unreadable(what),
            transactionReference = data.text("transaction_reference")
                ?: data.get("transaction")?.takeIf { it.isObject }?.text("reference"),
            status = status,
            providerStatus = providerStatus,
            amountKobo = data.get("amount")?.takeUnless { it.isNull }?.asLong() ?: throw unreadable(what),
            currency = data.text("currency"),
            failureReason = if (status == GatewayRefundStatus.FAILED) data.text("reason")?.ifBlank { null } else null,
            rawResponse = raw,
        )
    }

    // A clear refusal: Paystack answered 4xx with its own message, so no refund was created. 401/403 (our key)
    // and 408/429 (try later) are not about this refund, so they stay code 22.
    private fun rejectionOf(status: Int, body: String): String? {
        if (status !in 400..499 || status in setOf(401, 403, 408, 429)) return null
        val json = runCatching { jsonMapper.readTree(body) }.getOrNull() ?: return null
        if (json.get("status")?.asBoolean() != false) return null
        return json.text("message")?.ifBlank { null }
    }

    private fun authHeaders() = hashMapOf("Authorization" to "Bearer ${paystackProps.secretKey}")

    /** Each segment is encoded, because a reference can come from the customer's URL. */
    private fun url(path: String, segment: String? = null): String =
        paystackProps.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(path)
            .apply { segment?.let { addPathSegment(it) } }
            .build().toString()

    /** Network problems become a code 22. */
    private fun <T> send(what: String, request: () -> T): T =
        try {
            request()
        } catch (ex: IOException) {
            logger.error(ex) { "Paystack $what failed: could not reach Paystack" }
            throw ApiErrorException(PROVIDER_ERROR)
        }

    /** The "data" object (or array, for lists) of a successful answer. Anything else is logged and becomes a code 22. */
    private fun dataOf(body: String, what: String, expectArray: Boolean = false): JsonNode {
        val json = parse(body, what)
        val data = json?.get("data")
        val shapeOk = data != null && if (expectArray) data.isArray else data.isObject
        if (json?.get("status")?.asBoolean() != true || !shapeOk) {
            logger.error { "Paystack $what failed: $body" }
            throw ApiErrorException(PROVIDER_ERROR)
        }
        return data!!
    }

    // Paystack answers an unknown reference (or refund id) with status false and a "not found" code or message.
    private fun isUnknownReference(body: String): Boolean {
        val json = runCatching { jsonMapper.readTree(body) }.getOrNull() ?: return false
        if (json.get("status")?.asBoolean() != false) return false
        return json.text("code") == "transaction_not_found" ||
            json.text("message")?.contains("not found", ignoreCase = true) == true
    }

    private fun parse(body: String, what: String): JsonNode? =
        try {
            jsonMapper.readTree(body)
        } catch (ex: JacksonException) {
            logger.error { "Paystack $what answered with a body that is not JSON: ${body.take(500)}" }
            null
        }

    private fun unreadable(what: String, cause: Exception? = null): ApiErrorException {
        logger.error(cause) { "Paystack $what answered without the expected fields" }
        return ApiErrorException(PROVIDER_ERROR)
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

    companion object {
        const val PROVIDER_ERROR = "The payment provider could not be reached, please try again later"

        // Enough for every refund of one transaction (a full refund is made at most once, failures aside).
        const val LIST_PAGE_SIZE = 100

        private val JSON = "application/json".toMediaType()

        // Refund statuses: processed and failed are final; pending, processing and needs-attention
        // (Paystack needs the customer's account details) mean it is still in progress.
        fun refundStatusOf(paystackStatus: String?): GatewayRefundStatus = when (paystackStatus?.lowercase()) {
            "processed" -> GatewayRefundStatus.PROCESSED
            "failed" -> GatewayRefundStatus.FAILED
            else -> GatewayRefundStatus.PENDING
        }

        // Paystack statuses: success, failed, reversed and abandoned are final; ongoing, pending,
        // processing and queued mean the customer has not finished yet.
        fun statusOf(paystackStatus: String?): GatewayPaymentStatus = when (paystackStatus?.lowercase()) {
            "success" -> GatewayPaymentStatus.SUCCESS
            "failed", "reversed" -> GatewayPaymentStatus.FAILED
            "abandoned" -> GatewayPaymentStatus.ABANDONED
            else -> GatewayPaymentStatus.PENDING
        }
    }
}
