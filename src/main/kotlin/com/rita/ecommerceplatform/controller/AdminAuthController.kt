package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.exception.AccessNotAllowedException
import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.model.request.ChangePasswordRequest
import com.rita.ecommerceplatform.model.request.LoginRequest
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.AuthService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin auth", description = "Admin login and change password")
@RestController
@RequestMapping("/{stage}/admin/auth")
class AdminAuthController(private val authService: AuthService) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, @PathVariable stage: String): BaseDataResponse {
        return BaseDataResponse("00", "Successful", authService.adminLogin(request))
    }

    /** The admin is identified by the token, never by an ID in the request. */
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @PatchMapping("/password")
    fun changePassword(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: ChangePasswordRequest,
        @PathVariable stage: String,
    ): BaseDataResponse {
        val currentUser = CurrentUser.from(jwt)
        // Roles already restrict this URL to admin tokens; this guards against a mis-issued token.
        if (currentUser.userType != UserType.ADMIN) throw AccessNotAllowedException("Account is not active")
        return BaseDataResponse("00", "Successful", authService.changeAdminPassword(currentUser.id.toInt(), request))
    }
}
