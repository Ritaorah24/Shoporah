package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/** The requested record does not exist. */
class NotFoundException(message: String) : ApiException(HttpStatus.NOT_FOUND, message)
