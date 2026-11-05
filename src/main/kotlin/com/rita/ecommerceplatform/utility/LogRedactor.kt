package com.rita.ecommerceplatform.utility

import com.rita.ecommerceplatform.utility.constant.SensitiveDetails
import org.json.JSONArray
import org.json.JSONObject

/** Redact before truncation and never fall back to logging an unparseable body. */
object LogRedactor {
    fun body(raw: String, additionalFields: Collection<String> = emptyList()): String {
        if (raw.isBlank()) return ""
        val fields = (SensitiveDetails.SENSITIVE_PARAMETERS + additionalFields).map { it.lowercase() }.toSet()
        return try {
            val node: Any = when {
                raw.trimStart().startsWith("{") -> JSONObject(raw)
                raw.trimStart().startsWith("[") -> JSONArray(raw)
                else -> return "[non-JSON body omitted]"
            }
            mask(node, fields)
            node.toString()
        } catch (_: Exception) {
            "[unparseable body omitted]"
        }
    }

    private fun mask(node: Any?, fields: Set<String>): Any? =
        when (node) {
            is JSONObject -> node.apply {
                keys().asSequence().toList().forEach { key ->
                    val value = opt(key)
                    // Keep null sensitive fields as null so logs still show the field was absent.
                    put(key, if (key.lowercase() !in fields) mask(value, fields)
                             else if (value == null || value == JSONObject.NULL) JSONObject.NULL
                             else "******")
                }
            }
            is JSONArray -> node.apply { (0 until length()).forEach { put(it, mask(opt(it), fields)) } }
            // Legacy clients sometimes serialize nested request objects as JSON strings.
            is String -> if (node.trimStart().startsWith("{") || node.trimStart().startsWith("[")) body(node, fields) else node
            else -> node
        }
}
