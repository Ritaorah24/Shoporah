package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.BadGatewayException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime

/**
 * PaymentGateway backed by Paystack's REST API (https://paystack.com/docs/api/transaction/).
 * Paystack answers { "status": true|false, "message": ..., "data": {...} }.
 * Every failure (network, timeout, 4xx, 5xx, unreadable body) is logged and becomes a 502;
 * the secret key itself is never logged.
 */
@Component
class PaystackGateway(
    @Qualifier("paystackRestClient") private val restClient: RestClient,
    private val jsonMapper: JsonMapper,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(PaystackGateway::class.java)

    override fun initialize(request: GatewayInitRequest): GatewayInitResult {
        val body = buildMap {
            put("reference", request.reference)
            put("email", request.email)
            put("amount", request.amountKobo)
            put("currency", request.currency)
            request.callbackUrl?.takeIf { it.isNotBlank() }?.let { put("callback_url", it) }
        }
        val (status, responseBody) = send("initialize ${request.reference}") {
            restClient.post().uri("/transaction/initialize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(body))
        }
        val data = dataOf(status, responseBody, "initialize ${request.reference}")
        return GatewayInitResult(
            reference = data.text("reference") ?: request.reference,
            authorizationUrl = data.text("authorization_url") ?: throw unreadable("initialize ${request.reference}"),
            accessCode = data.text("access_code") ?: throw unreadable("initialize ${request.reference}"),
        )
    }

    override fun verify(reference: String): GatewayVerifyResult? {
        val (status, responseBody) = send("verify $reference") {
            restClient.get().uri("/transaction/verify/{reference}", reference)
        }
        if (isUnknownReference(status, responseBody)) return null
        val data = dataOf(status, responseBody, "verify $reference")
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

    /** Sends the request and returns the status and body, whatever the status. Network problems become a 502. */
    private fun send(what: String, request: () -> RestClient.RequestHeadersSpec<*>): Pair<HttpStatusCode, String> =
        try {
            request().exchange { _, response ->
                response.statusCode to response.body.readAllBytes().toString(Charsets.UTF_8)
            } ?: throw unreadable(what)
        } catch (ex: RestClientException) {
            log.error("Paystack {} failed: could not reach Paystack", what, ex)
            throw BadGatewayException(PROVIDER_ERROR)
        }

    /** The "data" object of a successful answer. Anything else is logged and becomes a 502. */
    private fun dataOf(status: HttpStatusCode, body: String, what: String): JsonNode {
        val json = parse(body, what)
        val data = json?.get("data")
        if (!status.is2xxSuccessful || json?.get("status")?.asBoolean() != true || data == null || !data.isObject) {
            log.error("Paystack {} failed: HTTP {} {}", what, status.value(), body)
            throw BadGatewayException(PROVIDER_ERROR)
        }
        return data
    }

    // Paystack answers an unknown reference with 400 (or 404) and code "transaction_not_found".
    private fun isUnknownReference(status: HttpStatusCode, body: String): Boolean {
        if (status.value() != 400 && status.value() != 404) return false
        val json = runCatching { jsonMapper.readTree(body) }.getOrNull() ?: return status.value() == 404
        return json.text("code") == "transaction_not_found" ||
            json.text("message")?.contains("not found", ignoreCase = true) == true
    }

    private fun parse(body: String, what: String): JsonNode? =
        try {
            jsonMapper.readTree(body)
        } catch (ex: JacksonException) {
            log.error("Paystack {} answered with a body that is not JSON: {}", what, body.take(500))
            null
        }

    private fun unreadable(what: String, cause: Exception? = null): BadGatewayException {
        log.error("Paystack {} answered without the expected fields", what, cause)
        return BadGatewayException(PROVIDER_ERROR)
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

    companion object {
        const val PROVIDER_ERROR = "The payment provider could not be reached, please try again later"

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
