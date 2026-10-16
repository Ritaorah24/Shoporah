package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/**
 * Base class for the API's own errors. GlobalExceptionHandler turns any subclass into an
 * ApiError with this status and message, so new exceptions need no extra handler code.
 */
abstract class ApiException(val status: HttpStatus, message: String) : RuntimeException(message)
