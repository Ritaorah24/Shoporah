package com.rita.ecommerceplatform.exception

import com.rita.ecommerceplatform.model.response.ApiFieldError
import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.model.response.BaseResponse
import com.rita.ecommerceplatform.service.ProductImageService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

private val log = KotlinLogging.logger {}

/**
 * Every error is HTTP 200 with BaseResponse(responseCode, responseMessage). The exception is the Paystack webhook,
 * which keeps real statuses so Paystack retries (PaystackWebhookController).
 * Extends ResponseEntityExceptionHandler so Spring MVC's own errors (bad JSON, unknown URL, wrong method, ...) keep
 * their specific message and headers such as Allow.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(UnauthorizedAccessException::class)
    fun handleUnauthorized(ex: UnauthorizedAccessException) =
        response("107", ex.message ?: "Access denied")

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(ex: BadRequestException): ResponseEntity<Any> {
        val message = ex.message ?: "Bad request"
        return ex.field?.let { field -> fieldErrors(message, listOf(ApiFieldError(field, message))) } ?: response("106", message)
    }

    @ExceptionHandler(UnableToLocateRecordException::class)
    fun handleNotFound(ex: UnableToLocateRecordException) =
        response("25", ex.message ?: "Unable to locate record")

    @ExceptionHandler(AccessNotAllowedException::class)
    fun handleAccessNotAllowed(ex: AccessNotAllowedException) =
        response("107", ex.message ?: "Access denied")

    @ExceptionHandler(IncorrectPasswordException::class)
    fun handleIncorrectPassword(ex: IncorrectPasswordException) =
        response("86", ex.message ?: "Invalid credential")

    @ExceptionHandler(ApiErrorException::class)
    fun handleApiError(ex: ApiErrorException) =
        response("22", ex.message ?: "Something went wrong")

    @ExceptionHandler(DuplicateTransactionException::class)
    fun handleDuplicate(ex: DuplicateTransactionException) =
        response("26", ex.message ?: "Duplicate record")

    @ExceptionHandler(RecordAlreadyExistException::class)
    fun handleRecordExists(ex: RecordAlreadyExistException) =
        response("117", ex.message ?: "Record already exists")

    /** A bug. The client gets a generic message; the stack trace goes to the log only. */
    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception, request: HttpServletRequest): ResponseEntity<Any> {
        log.error(ex) { "Unexpected error on ${request.method} ${request.requestURI}" }
        return response("22", "An unexpected error occurred")
    }

    /** @Valid failed: list each field and rule, never the rejected value (it may be a password). */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors = ex.bindingResult.fieldErrors
            .map { ApiFieldError(field = it.field, message = it.defaultMessage ?: "is invalid") }
            .sortedBy { it.field }
        return fieldErrors("Validation failed", errors)
    }

    /** Every other Spring MVC error: keep its headers (e.g. Allow on a wrong method) and its specific message. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val status = HttpStatus.resolve(statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        val message = when (ex) {
            is HttpMessageNotReadableException -> "Malformed or unreadable JSON request body"
            is NoResourceFoundException -> "No endpoint found for this URL"
            is HttpRequestMethodNotSupportedException -> "HTTP method ${ex.method} is not supported for this URL"
            // Uploads (product images) take multipart/form-data; every other endpoint takes JSON.
            is HttpMediaTypeNotSupportedException ->
                if (MediaType.MULTIPART_FORM_DATA in ex.supportedMediaTypes) "Content-Type must be multipart/form-data"
                else "Content-Type must be application/json"
            is MaxUploadSizeExceededException -> ProductImageService.TOO_LARGE
            is MissingServletRequestPartException -> "The '${ex.requestPartName}' file is required"
            else -> status.reasonPhrase
        }
        val code = when {
            ex is NoResourceFoundException -> "25"
            status.is5xxServerError -> "22"
            else -> "106"
        }
        return ResponseEntity.status(HttpStatus.OK).headers(headers).contentType(MediaType.APPLICATION_JSON)
            .body(BaseResponse(responseCode = code, responseMessage = message))
    }

    private fun fieldErrors(message: String, errors: List<ApiFieldError>): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
            .body(BaseDataResponse("106", message, mapOf("fieldErrors" to errors)))

    private fun response(code: String, message: String): ResponseEntity<Any> {
        log.info { "[EXCEPTION_RESPONSE:::::][responseCode=$code, responseMessage=$message]" }
        return ResponseEntity.status(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
            .body(BaseResponse(responseCode = code, responseMessage = message))
    }
}
