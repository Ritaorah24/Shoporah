package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.service.PaymentService
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Called by Paystack, not by our clients, so it needs no token (SecurityConfig) and is hidden from Swagger.
 * It is protected by the x-paystack-signature check instead. Set this URL in the Paystack dashboard.
 */
@Hidden
@RestController
@RequestMapping("/api/webhooks")
class PaystackWebhookController(private val paymentService: PaymentService) {

    /**
     * 200 when handled or ignored; 401 for a bad signature; 502 when Paystack could not be asked about the
     * payment, so Paystack sends the webhook again later.
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

    companion object {
        const val SIGNATURE_HEADER = "x-paystack-signature"
    }
}
