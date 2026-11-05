package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.Size

/** Optional body of POST /{stage}/customer/orders/{orderId}/cancel. The reason is saved in the status history. */
data class CancelOrderRequest(
    @field:Size(max = 500)
    val reason: String? = null,
)
