package com.rita.ecommerceplatform.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/** Body of POST /api/customer/coupons/validate: the code the customer typed, in any case. */
data class ValidateCouponRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 30)
    val code: String?,
)
