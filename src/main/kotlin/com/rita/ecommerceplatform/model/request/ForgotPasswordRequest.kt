package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/** Body of POST /api/auth/forgot-password. Same email rule as RegisterRequest. */
data class ForgotPasswordRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$")
    @field:Size(max = 254)
    val email: String?,
)
