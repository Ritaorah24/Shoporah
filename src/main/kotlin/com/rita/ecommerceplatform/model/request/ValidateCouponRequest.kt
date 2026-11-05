package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/** Body of POST /{stage}/customer/coupons/validate: the code the customer typed, in any case. */
data class ValidateCouponRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 30)
    val code: String?,
)
