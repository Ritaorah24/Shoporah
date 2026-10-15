package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/** The request is well-formed but breaks a business rule, e.g. a new password equal to the current one. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class BadRequestException(message: String) : RuntimeException(message)
