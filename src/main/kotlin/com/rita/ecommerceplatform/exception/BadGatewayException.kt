package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/** A service we depend on (the payment provider) failed or could not be reached. The details go to the log only. */
class BadGatewayException(message: String) : ApiException(HttpStatus.BAD_GATEWAY, message)
