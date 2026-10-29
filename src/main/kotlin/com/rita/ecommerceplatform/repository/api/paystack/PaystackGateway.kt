package com.rita.ecommerceplatform.repository.api.paystack

import com.rita.ecommerceplatform.exception.BadGatewayException
import com.rita.ecommerceplatform.repository.api.GatewayInitRequest
import com.rita.ecommerceplatform.repository.api.GatewayInitResult
import com.rita.ecommerceplatform.repository.api.GatewayPaymentStatus
import com.rita.ecommerceplatform.repository.api.GatewayRefund
import com.rita.ecommerceplatform.repository.api.GatewayRefundRequest
import com.rita.ecommerceplatform.repository.api.GatewayRefundStart
import com.rita.ecommerceplatform.repository.api.GatewayRefundStatus
import com.rita.ecommerceplatform.repository.api.GatewayVerifyResult
import com.rita.ecommerceplatform.repository.api.PaymentGateway
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

    // https://paystack.com/docs/api/refund/ : `transaction` takes the payment's reference.
    override fun refund(request: GatewayRefundRequest): GatewayRefundStart {
        val what = "refund ${request.transactionReference}"
        val body = buildMap {
            put("transaction", request.transactionReference)
            put("amount", request.amountKobo)
            put("currency", request.currency)
            request.merchantNote?.takeIf { it.isNotBlank() }?.let { put("merchant_note", it) }
        }
        val (status, responseBody) = send(what) {
            restClient.post().uri("/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(body))
        }
        rejectionOf(status, responseBody)?.let { message ->
            log.warn("Paystack {} was refused: HTTP {} {}", what, status.value(), message)
            return GatewayRefundStart.Rejected(message)
        }
        return GatewayRefundStart.Accepted(refundOf(dataOf(status, responseBody, what), responseBody, what))
    }

    override fun fetchRefund(providerRefundId: String): GatewayRefund? {
        val what = "fetch refund $providerRefundId"
        val (status, responseBody) = send(what) { restClient.get().uri("/refund/{id}", providerRefundId) }
        if (isUnknownReference(status, responseBody)) return null
        return refundOf(dataOf(status, responseBody, what), responseBody, what)
    }

    // Paystack filters the list by transaction id; the reference check below keeps only this payment's
    // refunds even if it did not (e.g. no id known), so another payment's refund is never taken for ours.
    override fun findRefunds(transactionId: String?, transactionReference: String): List<GatewayRefund> {
        val what = "list refunds of $transactionReference"
        val (status, responseBody) = send(what) {
            restClient.get().uri { uri ->
                uri.path("/refund").queryParam("perPage", LIST_PAGE_SIZE)
                transactionId?.let { uri.queryParam("transaction", it) }
                uri.build()
            }
        }
        val data = dataOf(status, responseBody, what, expectArray = true)
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
    // and 408/429 (try later) are not about this refund, so they stay 502s.
    private fun rejectionOf(status: HttpStatusCode, body: String): String? {
        if (!status.is4xxClientError || status.value() in setOf(401, 403, 408, 429)) return null
        val json = runCatching { jsonMapper.readTree(body) }.getOrNull() ?: return null
        if (json.get("status")?.asBoolean() != false) return null
        return json.text("message")?.ifBlank { null }
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

    /** The "data" object (or array, for lists) of a successful answer. Anything else is logged and becomes a 502. */
    private fun dataOf(status: HttpStatusCode, body: String, what: String, expectArray: Boolean = false): JsonNode {
        val json = parse(body, what)
        val data = json?.get("data")
        val shapeOk = data != null && if (expectArray) data.isArray else data.isObject
        if (!status.is2xxSuccessful || json?.get("status")?.asBoolean() != true || !shapeOk) {
            log.error("Paystack {} failed: HTTP {} {}", what, status.value(), body)
            throw BadGatewayException(PROVIDER_ERROR)
        }
        return data!!
    }

    // Paystack answers an unknown reference (or refund id) with 400 (or 404) and a "not found" code or message.
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

        // Enough for every refund of one transaction (a full refund is made at most once, failures aside).
        const val LIST_PAGE_SIZE = 100

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
