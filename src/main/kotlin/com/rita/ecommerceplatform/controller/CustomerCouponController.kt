package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.exception.AccessNotAllowedException
import com.rita.ecommerceplatform.config.SwaggerConfig
import com.rita.ecommerceplatform.model.request.ValidateCouponRequest
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.CouponService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Coupons", description = "Check a coupon code against the logged-in customer's cart")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
@RestController
@RequestMapping("/{stage}/customer/coupons")
class CustomerCouponController(private val couponService: CouponService) {

    /**
     * What the coupon would save on the current cart (code 106 with the reason if it cannot be used). Counts no use:
     * send the code as couponCode at checkout to apply it.
     */
    @PostMapping("/validate")
    fun validate(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: ValidateCouponRequest,
        @PathVariable stage: String,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", couponService.preview(customerId(jwt), request.code!!))
    }

    // Roles already restrict this URL to customer tokens; this guards against a mis-issued token.
    private fun customerId(jwt: Jwt): Long {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.CUSTOMER) throw AccessNotAllowedException("Account is not active")
        return currentUser.id
    }
}
