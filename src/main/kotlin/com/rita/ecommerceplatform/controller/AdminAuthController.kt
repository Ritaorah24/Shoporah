package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.dto.request.ChangePasswordRequest
import com.rita.ecommerceplatform.dto.request.LoginRequest
import com.rita.ecommerceplatform.dto.response.TokenResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.AuthService
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/auth")
class AdminAuthController(private val authService: AuthService) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): TokenResponse =
        authService.adminLogin(request)

    /** The admin is identified by the token, never by an ID in the request. */
    @PatchMapping("/password")
    fun changePassword(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): TokenResponse {
        val currentUser = CurrentUser.from(jwt)
        // Roles already restrict this URL to admin tokens; this guards against a mis-issued token.
        if (currentUser.userType != UserType.ADMIN) throw AccountNotActiveException()
        return authService.changeAdminPassword(currentUser.id.toInt(), request)
    }
}
