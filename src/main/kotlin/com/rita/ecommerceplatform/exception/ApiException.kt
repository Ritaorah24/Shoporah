package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/**
 * Base class for the API's own errors. GlobalExceptionHandler turns any subclass into an
 * ApiError with this status and message, so new exceptions need no extra handler code.
 * fieldErrors is optional: set it when the error belongs to one request field, so a form can show it there.
 */
abstract class ApiException(
    val status: HttpStatus,
    message: String,
    val fieldErrors: List<ApiFieldError>? = null,
) : RuntimeException(message)
