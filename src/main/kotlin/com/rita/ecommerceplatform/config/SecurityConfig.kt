package com.rita.ecommerceplatform.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain

/**
 * Minimal first version (Step 6): opens the auth and Swagger endpoints,
 * keeps everything else locked. JWT checking and the 401/403 handlers
 * are added in Step 8.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            // Stateless JSON API with bearer tokens: no cookies, so no CSRF and no server session.
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/api/auth/**", permitAll)
                // Only the admin login is open; other /api/admin/auth/* endpoints (e.g. change password) need a token.
                authorize("/api/admin/auth/login", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                // Spring forwards errors (400, 409, ...) to /error; it must be open or every error turns into 401.
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)
            }
        }
        return http.build()
    }

    // Strength 10, the same cost as the seed admin's hash in V9.
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(10)
}
