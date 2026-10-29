package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.dto.request.AdminCancelOrderRequest
import com.rita.ecommerceplatform.dto.request.RefundOrderRequest
import com.rita.ecommerceplatform.dto.request.UpdateOrderStatusRequest
import com.rita.ecommerceplatform.dto.response.AdminOrderResponse
import com.rita.ecommerceplatform.dto.response.AdminOrderSummaryResponse
import com.rita.ecommerceplatform.dto.response.PageResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.AdminOrderFilters
import com.rita.ecommerceplatform.service.AdminOrderService
import com.rita.ecommerceplatform.service.Paging
import com.rita.ecommerceplatform.service.RefundService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** Order fulfilment. SecurityConfig restricts every /api/admin URL to admins who have changed their password. */
@Tag(name = "Admin orders", description = "List every customer's orders and move paid orders through fulfilment")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderController(
    private val adminOrderService: AdminOrderService,
    private val refundService: RefundService,
) {

    /**
     * Newest first; page size is capped at 50. Every filter is optional and they combine (all must match):
     * status (e.g. CONFIRMED: waiting to be packed), paymentStatus (UNPAID, PAID, REFUNDED; CANCELLED + PAID = refund
     * needed), reference (starts with, any case: a full reference or ORD-20261029 for a day), customerId, customer
     * (part of the email or name), from / to (days placed, inclusive, yyyy-MM-dd).
     */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${Paging.DEFAULT_SIZE}") size: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) paymentStatus: String?,
        @RequestParam(required = false) reference: String?,
        @RequestParam(required = false) customerId: Long?,
        @RequestParam(required = false) customer: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): PageResponse<AdminOrderSummaryResponse> =
        adminOrderService.list(page, size, AdminOrderFilters(status, paymentStatus, reference, customerId, customer, from, to))

    @GetMapping("/{orderId}")
    fun get(@PathVariable orderId: Long): AdminOrderResponse = adminOrderService.get(orderId)

    /**
     * CONFIRMED → PROCESSING → SHIPPED → DELIVERED, one step at a time. A skipped, backward or repeated step
     * is 409; a status other than these three is 400 (cancel and refund come in V2).
     */
    @PatchMapping("/{orderId}/status")
    fun updateStatus(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable orderId: Long,
        @Valid @RequestBody request: UpdateOrderStatusRequest,
    ): AdminOrderResponse = adminOrderService.updateStatus(adminId(jwt), orderId, request)

    /**
     * Cancels an order that has not shipped, with a required reason. A paid order's stock goes back and the order
     * stays PAID (refund needed). Shipped or delivered orders are 409: they are refunded instead.
     */
    @PostMapping("/{orderId}/cancel")
    fun cancel(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable orderId: Long,
        @Valid @RequestBody request: AdminCancelOrderRequest,
    ): AdminOrderResponse = adminOrderService.cancel(adminId(jwt), orderId, request)

    /**
     * Refunds the whole order total to the customer through Paystack: a paid order that is CANCELLED, or DELIVERED
     * (optionally putting the returned items back in stock). 202: the refund is started, Paystack finishes it later;
     * see refunds[] on the order, and POST /api/admin/refunds/{refundId}/refresh to check on it.
     */
    @PostMapping("/{orderId}/refund")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun refund(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable orderId: Long,
        @Valid @RequestBody request: RefundOrderRequest,
    ): AdminOrderResponse = refundService.start(adminId(jwt), orderId, request)

    // Roles already restrict this URL to admin tokens; this guards against a mis-issued token.
    private fun adminId(jwt: Jwt): Int {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.ADMIN) throw AccountNotActiveException()
        return currentUser.id.toInt()
    }
}
