package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.exception.ApiErrorException
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.IncorrectPasswordException
import com.rita.ecommerceplatform.model.response.BaseResponse
import com.rita.ecommerceplatform.service.PaymentService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

/**
 * Called by Paystack, not by our clients, so it needs no token (SecurityConfig) and is hidden from Swagger.
 * It is protected by the x-paystack-signature check instead. Set this URL in the Paystack dashboard.
 */
@Hidden
@RestController
@RequestMapping("/webhook")
class PaystackWebhookController(private val paymentService: PaymentService) {

    /**
     * Payment (charge.success) and refund (refund.processed / refund.failed) events.
     * 200 when handled or ignored; 401 for a bad signature; 502 when Paystack could not be asked about the
     * payment or refund, so Paystack sends the webhook again later.
     */
    @PostMapping("/paystack")
    fun paystack(
        // The raw bytes: the signature is over the body exactly as sent, which parsing and re-writing would change.
        @RequestBody body: ByteArray,
        @RequestHeader(name = SIGNATURE_HEADER, required = false) signature: String?,
    ): ResponseEntity<Void> {
        paymentService.handleWebhook(body, signature)
        return ResponseEntity.ok().build()
    }

    /** Not HTTP 200 like the rest of the API: Paystack retries a webhook only when it gets a failure status. */
    @ExceptionHandler(IncorrectPasswordException::class)
    fun badSignature(ex: IncorrectPasswordException) = failure(HttpStatus.UNAUTHORIZED, "86", ex.message)

    @ExceptionHandler(BadRequestException::class)
    fun unreadable(ex: BadRequestException) = failure(HttpStatus.BAD_REQUEST, "106", ex.message)

    @ExceptionHandler(ApiErrorException::class)
    fun providerError(ex: ApiErrorException) = failure(HttpStatus.BAD_GATEWAY, "22", ex.message)

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<BaseResponse> {
        logger.error(ex) { "Unexpected error handling a Paystack webhook" }
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, "22", "An unexpected error occurred")
    }

    private fun failure(status: HttpStatus, code: String, message: String?): ResponseEntity<BaseResponse> =
        ResponseEntity.status(status).body(BaseResponse(responseCode = code, responseMessage = message ?: status.reasonPhrase))

    companion object {
        const val SIGNATURE_HEADER = "x-paystack-signature"
    }
}
