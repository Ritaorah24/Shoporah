package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/** The request clashes with existing data, e.g. an email that is already registered. */
@ResponseStatus(HttpStatus.CONFLICT)
class ConflictException(message: String) : RuntimeException(message)
