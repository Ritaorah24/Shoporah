package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/**
 * The request is well-formed but breaks a business rule, e.g. a new password equal to the current one.
 * With a field, the message is also sent as that field's error (e.g. couponCode).
 */
class BadRequestException(message: String, field: String? = null) :
    ApiException(HttpStatus.BAD_REQUEST, message, field?.let { listOf(ApiFieldError(it, message)) })
