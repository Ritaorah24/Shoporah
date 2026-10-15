package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.dto.request.LoginRequest
import com.rita.ecommerceplatform.dto.response.TokenResponse
import com.rita.ecommerceplatform.service.AuthService
import jakarta.validation.Valid
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
}
