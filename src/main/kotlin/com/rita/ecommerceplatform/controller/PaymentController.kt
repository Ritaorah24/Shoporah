package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.exception.AccessNotAllowedException
import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.model.request.InitializePaymentRequest
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.PaymentService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
     * responseMessage "Payment started" when new; "Successful" with the same payment when one is already open for the order.
     * The order is only marked paid after the payment is verified.
     */
    @PostMapping("/initialize")
    fun initialize(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: InitializePaymentRequest,
    ): BaseDataResponse {
        val result = paymentService.initialize(customerId(jwt), request.orderReference!!)
        return BaseDataResponse("00", if (result.created) "Payment started" else "Successful", result.payment)
    }

    /**
     * Asks Paystack what happened to the payment (call it after Paystack sends the customer back with ?reference=).
     * On success the order becomes CONFIRMED and PAID and stock is taken. Safe to call any number of times:
     * a payment is settled only once.
     */
    @GetMapping("/verify/{reference}")
    fun verify(@AuthenticationPrincipal jwt: Jwt, @PathVariable reference: String): BaseDataResponse {
        return BaseDataResponse("00", "Successful", paymentService.verify(customerId(jwt), reference.trim()))
    }

    // Roles already restrict /api/customer/** to customer tokens; this guards against a mis-issued token.
    private fun customerId(jwt: Jwt): Long {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.CUSTOMER) throw AccessNotAllowedException("Account is not active")
        return currentUser.id
    }
}
