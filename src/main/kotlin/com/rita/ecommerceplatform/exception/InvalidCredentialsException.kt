package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Wrong credentials. At login the default message is used for both a wrong email and a
 * wrong password, so login cannot reveal which emails exist.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
class InvalidCredentialsException(message: String = "Invalid email or password") : RuntimeException(message)
