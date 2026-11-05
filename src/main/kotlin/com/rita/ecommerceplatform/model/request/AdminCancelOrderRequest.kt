package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Body of POST /{stage}/admin/orders/{orderId}/cancel. The reason is required: it is the audit trail,
 * and the customer sees it in the status history and the email.
 */
data class AdminCancelOrderRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String?,
)
