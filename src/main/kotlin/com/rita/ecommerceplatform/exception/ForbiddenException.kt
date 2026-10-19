package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/** The caller is logged in but not allowed to do this, e.g. a customer making a change only an admin may make. */
class ForbiddenException(message: String) : ApiException(HttpStatus.FORBIDDEN, message)
