package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/**
 * Wrong credentials. At login the default message is used for both a wrong email and a
 * wrong password, so login cannot reveal which emails exist.
 */
class InvalidCredentialsException(message: String = "Invalid email or password") :
    ApiException(HttpStatus.UNAUTHORIZED, message)
