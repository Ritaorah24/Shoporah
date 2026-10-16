package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/** The request is well-formed but breaks a business rule, e.g. a new password equal to the current one. */
class BadRequestException(message: String) : ApiException(HttpStatus.BAD_REQUEST, message)
