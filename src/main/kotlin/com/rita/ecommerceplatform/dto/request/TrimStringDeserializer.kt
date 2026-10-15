package com.rita.ecommerceplatform.dto.request

import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer

/**
 * Trims surrounding whitespace while the JSON is read, so validation
 * (e.g. @Email) checks the trimmed value. Use only on fields where spaces
 * are never meaningful, e.g. email. Never on passwords.
 */
class TrimStringDeserializer : ValueDeserializer<String>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String =
        p.valueAsString.trim()
}
