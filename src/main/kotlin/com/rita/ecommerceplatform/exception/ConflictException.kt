package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/** The request clashes with existing data, e.g. an email that is already registered. */
class ConflictException(message: String) : ApiException(HttpStatus.CONFLICT, message)
