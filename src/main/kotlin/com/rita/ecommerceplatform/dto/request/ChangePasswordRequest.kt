package com.rita.ecommerceplatform.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/** Body of PATCH /api/admin/auth/password and PATCH /api/customer/me/password. */
data class ChangePasswordRequest(
    @field:NotBlank
    @field:Size(max = 72)
    val currentPassword: String?,

    // Same rules as RegisterRequest.password (D8).
    @field:NotBlank
    @field:Size(min = 8, max = 72)
    @field:Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one number")
    val newPassword: String?,
)
