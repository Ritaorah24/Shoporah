package com.rita.ecommerceplatform.exception

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Turns every exception that escapes a controller into an ApiError.
 * Errors raised in the security filter chain (missing/invalid token, wrong role) never
 * reach this class; the handlers in security/ write the same ApiError shape for those.
 *
 * Extends ResponseEntityExceptionHandler so Spring MVC's own errors (bad JSON, unknown URL,
 * wrong method, ...) keep their correct status; only the body is replaced.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /** Our own business errors: status and message come from the exception. */
    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException, request: HttpServletRequest): ResponseEntity<ApiError> =
        ResponseEntity.status(ex.status).body(apiError(ex.status, ex.message ?: ex.status.reasonPhrase, request.requestURI))

    /** A bug. The client gets a generic message; the stack trace goes to the log only. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.error("Unexpected error on {} {}", request.method, request.requestURI, ex)
        val status = HttpStatus.INTERNAL_SERVER_ERROR
        return ResponseEntity.status(status).body(apiError(status, "An unexpected error occurred", request.requestURI))
    }

    /** @Valid failed: list each field and rule, never the rejected value (it may be a password). */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val fieldErrors = ex.bindingResult.fieldErrors
            .map { ApiFieldError(field = it.field, message = it.defaultMessage ?: "is invalid") }
            .sortedBy { it.field }
        // Field names only: the default Spring log line printed rejected values, including passwords.
        log.debug("Validation failed on {}: {}", pathOf(request), fieldErrors.map { it.field })
        val httpStatus = toHttpStatus(status)
        return ResponseEntity.status(httpStatus).headers(headers)
            .body(apiError(httpStatus, "Validation failed", pathOf(request), fieldErrors))
    }

    /** Every other Spring MVC error: keep its status and headers (e.g. Allow on 405), use our body. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val status = toHttpStatus(statusCode)
        val message = when (ex) {
            is HttpMessageNotReadableException -> "Malformed or unreadable JSON request body"
            is NoResourceFoundException -> "No endpoint found for this URL"
            is HttpRequestMethodNotSupportedException -> "HTTP method ${ex.method} is not supported for this URL"
            is HttpMediaTypeNotSupportedException -> "Content-Type must be application/json"
            else -> status.reasonPhrase
        }
        return ResponseEntity.status(status).headers(headers).body(apiError(status, message, pathOf(request)))
    }

    private fun apiError(status: HttpStatus, message: String, path: String, fieldErrors: List<ApiFieldError>? = null) =
        ApiError(status = status.value(), error = status.reasonPhrase, message = message, path = path, fieldErrors = fieldErrors)

    private fun toHttpStatus(statusCode: HttpStatusCode): HttpStatus =
        HttpStatus.resolve(statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR

    private fun pathOf(request: WebRequest): String =
        (request as? ServletWebRequest)?.request?.requestURI ?: ""
}
