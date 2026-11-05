package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Body of POST /{stage}/auth/register.
 * Fields are nullable so a missing field reaches validation (clear code 106 message)
 * instead of failing earlier in JSON parsing.
 */
data class RegisterRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val firstName: String?,

    @field:NotBlank
    @field:Size(max = 100)
    val lastName: String?,

    // Trimmed before validation, so " ada@example.com " is accepted as "ada@example.com".
    // The regexp is checked on top of @Email's own rules, which allow a domain without a dot
    // (ada@localhost, ada@gmailcom) that payment providers reject: the domain needs a dot and 2+ final letters.
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$")
    @field:Size(max = 254)
    val email: String?,

    // Optional "+", then 10-15 digits, e.g. +2348012345678 or 08012345678
    @field:NotBlank
    @field:Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optionally starting with +")
    val phoneNumber: String?,

    // At least 8 characters with a letter and a number (D8). Max 72: BCrypt ignores bytes beyond 72.
    @field:NotBlank
    @field:Size(min = 8, max = 72)
    @field:Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one number")
    val password: String?,
)
