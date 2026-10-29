package com.rita.ecommerceplatform.repository.api.paystack

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The parts of a Paystack webhook we read: { "event": "charge.success", "data": { "reference": ..., ... } }.
 * Refund events (checkpoint 2.2) carry the refund's id and the payment's reference instead:
 * { "event": "refund.processed", "data": { "id": ..., "transaction_reference": ..., ... } }.
 * Everything else in the body is ignored; what happened is always asked of Paystack again.
 */
data class PaystackWebhookEvent(
    val event: String,
    val reference: String?,
    val refundId: String? = null,
    val transactionReference: String? = null,
) {

    val isChargeSuccess: Boolean get() = event == CHARGE_SUCCESS

    val isRefundEvent: Boolean get() = event.startsWith(REFUND_PREFIX)

    // refund.pending and refund.processing change nothing for us: only these two finish a refund.
    val isRefundFinal: Boolean get() = event == REFUND_PROCESSED || event == REFUND_FAILED

    companion object {
        const val CHARGE_SUCCESS = "charge.success"
        const val REFUND_PREFIX = "refund."
        const val REFUND_PROCESSED = "refund.processed"
        const val REFUND_FAILED = "refund.failed"

        /** The event in the body, or null if the body is not JSON or has no "event". */
        fun parse(jsonMapper: JsonMapper, body: ByteArray): PaystackWebhookEvent? {
            val json = try {
                jsonMapper.readTree(body)
            } catch (ex: JacksonException) {
                return null
            }
            val event = json?.get("event")?.takeIf { it.isString }?.asString() ?: return null
            val data = json.get("data")
            if (event.startsWith(REFUND_PREFIX)) {
                return PaystackWebhookEvent(
                    event = event,
                    reference = null,
                    // A number in Paystack's examples; read as text either way.
                    refundId = data?.get("id")?.takeIf { it.isString || it.isNumber }?.asString(),
                    transactionReference = data.string("transaction_reference"),
                )
            }
            return PaystackWebhookEvent(event, data.string("reference"))
        }

        private fun JsonNode?.string(field: String): String? = this?.get(field)?.takeIf { it.isString }?.asString()
    }
}
