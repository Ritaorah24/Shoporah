package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.dto.request.CancelOrderRequest
import com.rita.ecommerceplatform.dto.request.CheckoutRequest
import com.rita.ecommerceplatform.dto.response.OrderResponse
import com.rita.ecommerceplatform.dto.response.OrderSummaryResponse
import com.rita.ecommerceplatform.dto.response.PageResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.OrderService
import com.rita.ecommerceplatform.service.Paging
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
        @Valid @RequestBody(required = false) request: CheckoutRequest?,
    ): ResponseEntity<OrderResponse> {
        val result = orderService.checkout(customerId(jwt), parseKey(idempotencyKey), request)
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(result.order)
    }

    /** The customer's orders, newest first. Page size is capped at 50. */
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${Paging.DEFAULT_SIZE}") size: Int,
    ): PageResponse<OrderSummaryResponse> = orderService.list(customerId(jwt), page, size)

    /** Items, totals, delivery address and status history of one of the customer's own orders. */
    @GetMapping("/{orderId}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: Long): OrderResponse =
        orderService.get(customerId(jwt), orderId)

    /**
     * Cancels one of the customer's own orders while it is PENDING and UNPAID (409 otherwise, e.g. once paid).
     * The body is optional: {"reason": "..."}.
     */
    @PostMapping("/{orderId}/cancel")
    fun cancel(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable orderId: Long,
        @Valid @RequestBody(required = false) request: CancelOrderRequest?,
    ): OrderResponse = orderService.cancel(customerId(jwt), orderId, request)

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
