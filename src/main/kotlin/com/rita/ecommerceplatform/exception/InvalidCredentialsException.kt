package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/** Wrong email or wrong password. One message for both, so login cannot reveal which emails exist. */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
class InvalidCredentialsException : RuntimeException("Invalid email or password")
