package com.rita.ecommerceplatform.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Body of PATCH .../{id}/status for categories, products, delivery methods and coupons: activate or deactivate.
 * DELETED is not accepted here; deleting goes through DELETE so it is always deliberate.
 * A String rather than an enum, so a wrong value gets a clear field error instead of "malformed JSON".
 */
data class UpdateStatusRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Pattern(regexp = "(?i)ACTIVE|INACTIVE", message = "must be ACTIVE or INACTIVE")
    val status: String?,
)
