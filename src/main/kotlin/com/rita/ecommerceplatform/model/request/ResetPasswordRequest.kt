package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/** Body of POST /api/auth/reset-password. The token is the one from the reset email's link. */
data class ResetPasswordRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 100)
    val token: String?,

    // Same rules as RegisterRequest.password (D8).
    @field:NotBlank
    @field:Size(min = 8, max = 72)
    @field:Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one number")
    val newPassword: String?,
)
