package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Body of PATCH /{stage}/admin/orders/{orderId}/status, which only moves a paid order along fulfilment:
 * cancelling has its own endpoint (POST .../cancel, which restores stock), and refunds come separately.
 * A String rather than an enum, so a wrong value gets a clear field error instead of "malformed JSON".
 */
data class UpdateOrderStatusRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Pattern(regexp = "(?i)PROCESSING|SHIPPED|DELIVERED", message = "must be PROCESSING, SHIPPED or DELIVERED")
    val status: String?,

    // Optional, e.g. the courier and tracking number. Blank is stored as no note.
    @field:Size(max = 500)
    val note: String? = null,
)
