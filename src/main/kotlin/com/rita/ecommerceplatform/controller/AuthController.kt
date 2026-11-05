package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.request.ForgotPasswordRequest
import com.rita.ecommerceplatform.model.request.LoginRequest
import com.rita.ecommerceplatform.model.request.RegisterRequest
import com.rita.ecommerceplatform.model.request.ResetPasswordRequest
import com.rita.ecommerceplatform.model.response.CustomerProfileResponse
import com.rita.ecommerceplatform.model.response.MessageResponse
import com.rita.ecommerceplatform.model.response.TokenResponse
import com.rita.ecommerceplatform.service.AuthService
import com.rita.ecommerceplatform.service.PasswordResetService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Customer auth", description = "Register, log in and reset a forgotten password as a customer")
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val passwordResetService: PasswordResetService,
) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterRequest): CustomerProfileResponse =
        authService.register(request)

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): TokenResponse =
        authService.login(request)

    /** Same reply whether or not the email has an account, so it cannot be used to find accounts. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun forgotPassword(@Valid @RequestBody request: ForgotPasswordRequest): MessageResponse {
        passwordResetService.requestReset(request)
        return MessageResponse(PasswordResetService.FORGOT_REPLY)
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest) =
        passwordResetService.resetPassword(request)
}
