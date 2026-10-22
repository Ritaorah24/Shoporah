package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.dto.request.InitializePaymentRequest
import com.rita.ecommerceplatform.dto.response.PaymentInitResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.PaymentService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Paying for the logged-in customer's orders. */
@Tag(name = "Payments", description = "Paying for the logged-in customer's orders")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/customer/payments")
class PaymentController(private val paymentService: PaymentService) {

    /**
     * Starts paying for a PENDING order and returns the Paystack page to send the customer to.
     * 201 when a new payment is started; 200 with the same payment when one is already open for the order.
     * The order is only marked paid after the payment is verified.
     */
    @PostMapping("/initialize")
    fun initialize(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: InitializePaymentRequest,
    ): ResponseEntity<PaymentInitResponse> {
        val result = paymentService.initialize(customerId(jwt), request.orderReference!!)
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(result.payment)
    }

    // Roles already restrict /api/customer/** to customer tokens; this guards against a mis-issued token.
    private fun customerId(jwt: Jwt): Long {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.CUSTOMER) throw AccountNotActiveException()
        return currentUser.id
    }
}
