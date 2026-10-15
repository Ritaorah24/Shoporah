package com.rita.ecommerceplatform.exception

import java.time.Instant

/** The one JSON error shape returned by the API. Step 10 adds field-level validation errors. */
data class ApiError(
    val timestamp: Instant = Instant.now(),
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
)
