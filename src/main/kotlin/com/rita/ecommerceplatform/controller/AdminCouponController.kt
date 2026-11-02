package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.dto.request.CreateCouponRequest
import com.rita.ecommerceplatform.dto.request.UpdateCouponRequest
import com.rita.ecommerceplatform.dto.request.UpdateStatusRequest
import com.rita.ecommerceplatform.dto.response.AdminCouponResponse
import com.rita.ecommerceplatform.dto.response.PageResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.CouponService
import com.rita.ecommerceplatform.service.Paging
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Coupon management. SecurityConfig restricts every /api/admin URL to admins who have changed their password. */
@Tag(name = "Admin coupons", description = "Create, edit, activate/deactivate and delete discount coupons")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/admin/coupons")
class AdminCouponController(private val couponService: CouponService) {

    /**
     * Newest first; page size is capped at 50. status is ACTIVE or INACTIVE (default: both; deleted coupons never show);
     * code matches the start of the code, ignoring case. Each coupon's state says whether it works right now.
     */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${Paging.DEFAULT_SIZE}") size: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) code: String?,
    ): PageResponse<AdminCouponResponse> = couponService.list(page, size, status, code)

    @GetMapping("/{couponId}")
    fun get(@PathVariable couponId: Int): AdminCouponResponse = couponService.get(couponId)

    /** The creating admin comes from the token, never from the request body. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateCouponRequest,
    ): AdminCouponResponse = couponService.create(adminId(jwt), request)

    @PatchMapping("/{couponId}")
    fun update(
        @PathVariable couponId: Int,
        @Valid @RequestBody request: UpdateCouponRequest,
    ): AdminCouponResponse = couponService.update(couponId, request)

    @PatchMapping("/{couponId}/status")
    fun updateStatus(
        @PathVariable couponId: Int,
        @Valid @RequestBody request: UpdateStatusRequest,
    ): AdminCouponResponse = couponService.updateStatus(couponId, request)

    /** Soft delete: the coupon disappears from the list and its code can never be used again. */
    @DeleteMapping("/{couponId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable couponId: Int) = couponService.delete(couponId)

    // Roles already restrict this URL to admin tokens; this guards against a mis-issued token.
    private fun adminId(jwt: Jwt): Int {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.ADMIN) throw AccountNotActiveException()
        return currentUser.id.toInt()
    }
}
