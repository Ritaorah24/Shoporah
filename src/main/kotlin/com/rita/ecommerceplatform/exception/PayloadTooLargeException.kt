package com.rita.ecommerceplatform.exception

import org.springframework.http.HttpStatus

/** An uploaded file is bigger than allowed (413). */
class PayloadTooLargeException(message: String) : ApiException(HttpStatus.PAYLOAD_TOO_LARGE, message)
