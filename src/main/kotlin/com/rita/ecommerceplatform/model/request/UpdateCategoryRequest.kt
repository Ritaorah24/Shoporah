package com.rita.ecommerceplatform.model.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Body of PATCH /api/admin/categories/{id}. Every field is optional: a field that is not sent
 * (null) keeps its current value. Status is changed through its own endpoint, not here.
 */
data class UpdateCategoryRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:Size(max = 100)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val name: String? = null,

    // "" (or only spaces) clears the description; leaving the field out keeps it.
    @field:Size(max = 1000)
    val description: String? = null,
) {
    // Unknown fields (e.g. "status") are collected so validation can reject them instead of ignoring them.
    @JsonIgnore
    private val unknownFields = mutableMapOf<String, Any?>()

    @JsonAnySetter
    fun addUnknownField(name: String, value: Any?) {
        unknownFields[name] = value
    }

    @get:JsonIgnore
    @get:AssertTrue(message = "only name and description can be updated (use PATCH .../status to change the status)")
    val onlyKnownFields: Boolean
        get() = unknownFields.isEmpty()

    @get:JsonIgnore
    @get:AssertTrue(message = "send at least one of name, description")
    val hasSomethingToUpdate: Boolean
        get() = name != null || description != null
}
