package com.rita.ecommerceplatform.model.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Body of PATCH /{stage}/customer/me. Every field is optional: a field that is not sent
 * (null) keeps its current value. Email cannot be changed here (D6).
 * Validators such as @Pattern and @Size accept null, so they only check fields that are sent.
 */
data class UpdateProfileRequest(
    @field:Size(max = 100)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val firstName: String? = null,

    @field:Size(max = 100)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val lastName: String? = null,

    // Can be changed but not removed: registration requires a phone number.
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optionally starting with +")
    val phoneNumber: String? = null,
) {
    // Spring Boot ignores unknown JSON fields by default, so {"email": "..."} would "succeed"
    // silently. Collecting them here lets validation reject them with a clear code 106 instead.
    @JsonIgnore
    private val unknownFields = mutableMapOf<String, Any?>()

    @JsonAnySetter
    fun addUnknownField(name: String, value: Any?) {
        unknownFields[name] = value
    }

    @get:JsonIgnore
    @get:AssertTrue(message = "only firstName, lastName and phoneNumber can be updated (email cannot be changed)")
    val onlyKnownFields: Boolean
        get() = unknownFields.isEmpty()

    @get:JsonIgnore
    @get:AssertTrue(message = "send at least one of firstName, lastName, phoneNumber")
    val hasSomethingToUpdate: Boolean
        get() = firstName != null || lastName != null || phoneNumber != null
}
