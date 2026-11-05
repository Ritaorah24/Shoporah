package com.rita.ecommerceplatform.exception

/** With a field, the message is also sent as that field's error (e.g. couponCode), so a form can show it there. */
data class BadRequestException(override val message: String? = null, val field: String? = null) : RuntimeException(message)
data class UnauthorizedAccessException(override val message: String? = null) : RuntimeException(message)
data class AccessNotAllowedException(override val message: String? = null) : RuntimeException(message)
data class UnableToLocateRecordException(override val message: String? = null) : RuntimeException(message)
data class IncorrectPasswordException(override val message: String? = null) : RuntimeException(message)
data class ApiErrorException(override val message: String? = null) : RuntimeException(message)
data class DuplicateTransactionException(override val message: String? = null) : RuntimeException(message)
data class RecordAlreadyExistException(override val message: String? = null) : RuntimeException(message)
