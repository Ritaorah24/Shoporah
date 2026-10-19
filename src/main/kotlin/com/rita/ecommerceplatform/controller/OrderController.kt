package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.dto.request.CheckoutRequest
import com.rita.ecommerceplatform.dto.response.OrderResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.OrderService
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** The logged-in customer's orders. */
@Tag(name = "Orders", description = "Checkout and the logged-in customer's orders")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/customer/orders")
class OrderController(private val orderService: OrderService) {

    /**
     * Places an order from the cart. 201 when created; 200 with the same order when this
     * Idempotency-Key was already used (a retry), so retrying never creates a second order.
     */
    @PostMapping
    fun checkout(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "A new UUID per checkout attempt; send the same one again when retrying")
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: CheckoutRequest?,
    ): ResponseEntity<OrderResponse> {
        val result = orderService.checkout(customerId(jwt), parseKey(idempotencyKey), request)
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(result.order)
    }

    // Read as a String and checked here, so a missing or malformed key gets a clear message.
    private fun parseKey(value: String?): UUID {
        if (value.isNullOrBlank()) throw BadRequestException("$IDEMPOTENCY_KEY header is required")
        return try {
            UUID.fromString(value.trim())
        } catch (e: IllegalArgumentException) {
            throw BadRequestException("$IDEMPOTENCY_KEY header must be a UUID")
        }
    }

    // Roles already restrict /api/customer/** to customer tokens; this guards against a mis-issued token.
    private fun customerId(jwt: Jwt): Long {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.CUSTOMER) throw AccountNotActiveException()
        return currentUser.id
    }

    companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
