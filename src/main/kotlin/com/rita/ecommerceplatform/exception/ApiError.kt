package com.rita.ecommerceplatform.exception

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

/** The one JSON error shape returned by the API. fieldErrors is only sent for validation errors. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val timestamp: Instant = Instant.now(),
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val fieldErrors: List<ApiFieldError>? = null,
)

/** One failed validation rule. The rejected value is never included (it may be a password). */
data class ApiFieldError(
    val field: String,
    val message: String,
)
