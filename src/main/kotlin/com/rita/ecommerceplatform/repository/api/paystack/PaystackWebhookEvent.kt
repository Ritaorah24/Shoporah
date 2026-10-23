package com.rita.ecommerceplatform.repository.api.paystack

import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/**
 * The parts of a Paystack webhook we read: { "event": "charge.success", "data": { "reference": ..., ... } }.
 * Everything else in the body is ignored; whether the payment succeeded is always asked of Paystack again.
 */
data class PaystackWebhookEvent(val event: String, val reference: String?) {

    val isChargeSuccess: Boolean get() = event == CHARGE_SUCCESS

    companion object {
        const val CHARGE_SUCCESS = "charge.success"

        /** The event in the body, or null if the body is not JSON or has no "event". */
        fun parse(jsonMapper: JsonMapper, body: ByteArray): PaystackWebhookEvent? {
            val json = try {
                jsonMapper.readTree(body)
            } catch (ex: JacksonException) {
                return null
            }
            val event = json?.get("event")?.takeIf { it.isString }?.asString() ?: return null
            val reference = json.get("data")?.get("reference")?.takeIf { it.isString }?.asString()
            return PaystackWebhookEvent(event, reference)
        }
    }
}
