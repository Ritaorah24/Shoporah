package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Body of POST /{stage}/admin/orders/{orderId}/refund. The whole order total is refunded (no partial refunds in V2).
 * The reason is required: it is the audit trail and is sent to the provider as the merchant note.
 * restock puts the sold items back in stock once the refund is processed; only for delivered orders that
 * were returned (a cancelled order's stock was already put back when it was cancelled).
 */
data class RefundOrderRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String?,
    val restock: Boolean = false,
)
