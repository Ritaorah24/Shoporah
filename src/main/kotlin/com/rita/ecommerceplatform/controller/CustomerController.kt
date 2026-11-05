package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.model.request.ChangePasswordRequest
import com.rita.ecommerceplatform.model.request.UpdateProfileRequest
import com.rita.ecommerceplatform.model.response.CustomerProfileResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.AuthService
import com.rita.ecommerceplatform.service.CustomerService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** "me" endpoints: the customer is always the one in the token, never an ID from the request. */
@Tag(name = "Customer profile", description = "The logged-in customer's own profile")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/customer")
class CustomerController(
    private val customerService: CustomerService,
    private val authService: AuthService,
) {

    @GetMapping("/me")
    fun getMe(@AuthenticationPrincipal jwt: Jwt): CustomerProfileResponse =
        customerService.getProfile(customerId(jwt))

    @PatchMapping("/me")
    fun updateMe(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: UpdateProfileRequest,
    ): CustomerProfileResponse =
        customerService.updateProfile(customerId(jwt), request)

    /** Needs the current password; the token in use stays valid until it expires. */
    @PatchMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: ChangePasswordRequest,
    ) = authService.changeCustomerPassword(customerId(jwt), request)

    // Roles already restrict /api/customer/** to customer tokens; this guards against a mis-issued token.
    private fun customerId(jwt: Jwt): Long {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.CUSTOMER) throw AccountNotActiveException()
        return currentUser.id
    }
}
