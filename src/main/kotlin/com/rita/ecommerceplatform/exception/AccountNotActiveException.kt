package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/** Correct credentials, but the account is suspended, locked, disabled or deleted. */
@ResponseStatus(HttpStatus.FORBIDDEN)
class AccountNotActiveException : RuntimeException("Account is not active")
