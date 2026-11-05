package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.NotBlank
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Body of POST /{stage}/customer/payments/initialize. There is deliberately no amount or currency field:
 * both come from the order, and any such field a client sends is ignored.
 */
data class InitializePaymentRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    val orderReference: String?,
)
