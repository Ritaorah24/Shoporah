package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/** A feature that needs an optional outside service which is not configured here (e.g. image storage). 503. */
class ServiceUnavailableException(message: String) : ApiException(HttpStatus.SERVICE_UNAVAILABLE, message)
