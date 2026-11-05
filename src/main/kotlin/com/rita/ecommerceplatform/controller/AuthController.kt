package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.model.response.BaseResponse
import com.rita.ecommerceplatform.model.request.ForgotPasswordRequest
import com.rita.ecommerceplatform.model.request.LoginRequest
import com.rita.ecommerceplatform.model.request.RegisterRequest
import com.rita.ecommerceplatform.model.request.ResetPasswordRequest
import com.rita.ecommerceplatform.service.AuthService
import com.rita.ecommerceplatform.service.PasswordResetService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Customer auth", description = "Register, log in and reset a forgotten password as a customer")
@RestController
@RequestMapping("/{stage}/auth")
class AuthController(
    private val authService: AuthService,
    private val passwordResetService: PasswordResetService,
) {

    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest, @PathVariable stage: String): BaseDataResponse {
        return BaseDataResponse("00", "Successful", authService.register(request))
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, @PathVariable stage: String): BaseDataResponse {
        return BaseDataResponse("00", "Successful", authService.login(request))
    }

    /** Same reply whether or not the email has an account, so it cannot be used to find accounts. */
    @PostMapping("/forgot-password")
    fun forgotPassword(@Valid @RequestBody request: ForgotPasswordRequest, @PathVariable stage: String): BaseResponse {
        passwordResetService.requestReset(request)
        return BaseResponse(responseMessage = PasswordResetService.FORGOT_REPLY)
    }

    @PostMapping("/reset-password")
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest, @PathVariable stage: String): BaseResponse {
        passwordResetService.resetPassword(request)
        return BaseResponse()
    }
}
