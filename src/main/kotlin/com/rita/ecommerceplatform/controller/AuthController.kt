package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.dto.request.LoginRequest
import com.rita.ecommerceplatform.dto.request.RegisterRequest
import com.rita.ecommerceplatform.dto.response.CustomerProfileResponse
import com.rita.ecommerceplatform.dto.response.TokenResponse
import com.rita.ecommerceplatform.service.AuthService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Customer auth", description = "Register and log in as a customer")
@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterRequest): CustomerProfileResponse =
        authService.register(request)

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): TokenResponse =
        authService.login(request)
}
