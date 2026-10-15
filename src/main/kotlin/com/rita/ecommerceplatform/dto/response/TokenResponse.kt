package com.rita.ecommerceplatform.dto.response

import com.fasterxml.jackson.annotation.JsonInclude

/** Login result. mustChangePassword is only sent for admins (left out of the JSON when null). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val mustChangePassword: Boolean? = null,
)
