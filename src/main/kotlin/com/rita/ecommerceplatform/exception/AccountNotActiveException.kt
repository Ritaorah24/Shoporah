package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/** Correct credentials, but the account is suspended, locked, disabled or deleted. */
class AccountNotActiveException : ApiException(HttpStatus.FORBIDDEN, "Account is not active")
