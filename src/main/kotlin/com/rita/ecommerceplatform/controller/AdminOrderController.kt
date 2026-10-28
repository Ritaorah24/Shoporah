package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.dto.request.AdminCancelOrderRequest
import com.rita.ecommerceplatform.dto.request.UpdateOrderStatusRequest
import com.rita.ecommerceplatform.dto.response.AdminOrderResponse
import com.rita.ecommerceplatform.dto.response.AdminOrderSummaryResponse
import com.rita.ecommerceplatform.dto.response.PageResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.AdminOrderService
import com.rita.ecommerceplatform.service.Paging
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Order fulfilment. SecurityConfig restricts every /api/admin URL to admins who have changed their password. */
@Tag(name = "Admin orders", description = "List every customer's orders and move paid orders through fulfilment")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderController(private val adminOrderService: AdminOrderService) {

    /** Newest first. Page size is capped at 50; status filters to one status, e.g. CONFIRMED (waiting to be packed). */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${Paging.DEFAULT_SIZE}") size: Int,
        @RequestParam(required = false) status: String?,
    ): PageResponse<AdminOrderSummaryResponse> = adminOrderService.list(page, size, status)

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

    // Roles already restrict this URL to admin tokens; this guards against a mis-issued token.
    private fun adminId(jwt: Jwt): Int {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.ADMIN) throw AccountNotActiveException()
        return currentUser.id.toInt()
    }
}
