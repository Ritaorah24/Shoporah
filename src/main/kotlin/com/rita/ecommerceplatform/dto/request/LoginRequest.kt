package com.rita.ecommerceplatform.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Body of POST /api/auth/login and POST /api/admin/auth/login.
 * No password-strength rules here: if the rules change, older passwords must still log in.
 */
data class LoginRequest(
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    val email: String?,

    // Max 72 only because BCrypt cannot use more; registration enforces the same limit.
    @field:NotBlank
    @field:Size(max = 72)
    val password: String?,
)
