package com.rita.ecommerceplatform.config

import com.rita.ecommerceplatform.security.JsonAccessDeniedHandler
import com.rita.ecommerceplatform.security.JsonAuthenticationEntryPoint
import com.rita.ecommerceplatform.security.PASSWORD_CHANGE_REQUIRED
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpMethod
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext

/**
 * Every request with "Authorization: Bearer <token>" is verified by the JwtDecoder (JwtConfig).
 * Rules are checked top to bottom; the first matching rule decides.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val authenticationEntryPoint: JsonAuthenticationEntryPoint,
    private val accessDeniedHandler: JsonAccessDeniedHandler,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            // Stateless JSON API with bearer tokens: no cookies, so no CSRF and no server session.
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/api/auth/**", permitAll)
                // Only the admin login is open; other /api/admin/auth/* endpoints need a token.
                authorize("/api/admin/auth/login", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                // Spring forwards errors (400, 409, ...) to /error; it must be open or every error turns into 401.
                authorize("/error", permitAll)
                // Public catalogue browsing: GET only, so writes to these URLs still need a token.
                authorize(HttpMethod.GET, "/api/categories/**", permitAll)

                authorize("/api/customer/**", hasRole("CUSTOMER"))
                // Allowed even while MUST_CHANGE: it is how the admin clears it (D4).
                authorize(HttpMethod.PATCH, "/api/admin/auth/password", hasAnyRole("ADMIN", "SUPER_ADMIN"))
                authorize("/api/admin/**", adminWithChangedPassword)

                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                jwt { jwtAuthenticationConverter = jwtAuthenticationConverter() }
                authenticationEntryPoint = this@SecurityConfig.authenticationEntryPoint
                accessDeniedHandler = this@SecurityConfig.accessDeniedHandler
            }
            exceptionHandling {
                authenticationEntryPoint = this@SecurityConfig.authenticationEntryPoint
                accessDeniedHandler = this@SecurityConfig.accessDeniedHandler
            }
        }
        return http.build()
    }

    // Strength 10, the same cost as the seed admin's hash in V9.
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(10)

    /** ADMIN or SUPER_ADMIN, and no pending password change (D4). */
    private val adminWithChangedPassword = AuthorizationManager<RequestAuthorizationContext> { authentication, _ ->
        val authorities = authentication.get()?.authorities.orEmpty().map { it.authority }
        val isAdmin = "ROLE_ADMIN" in authorities || "ROLE_SUPER_ADMIN" in authorities
        AuthorizationDecision(isAdmin && PASSWORD_CHANGE_REQUIRED !in authorities)
    }

    /**
     * Token claims to Spring authorities: "roles": ["SUPER_ADMIN"] becomes ROLE_SUPER_ADMIN,
     * and "mustChangePassword": true adds PASSWORD_CHANGE_REQUIRED.
     */
    private fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val rolesConverter = JwtGrantedAuthoritiesConverter().apply {
            setAuthoritiesClaimName("roles")
            setAuthorityPrefix("ROLE_")
        }
        return JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(Converter<Jwt, Collection<GrantedAuthority>> { jwt ->
                val authorities = rolesConverter.convert(jwt).orEmpty().toMutableList()
                if (jwt.getClaimAsBoolean("mustChangePassword") == true) {
                    authorities.add(SimpleGrantedAuthority(PASSWORD_CHANGE_REQUIRED))
                }
                authorities
            })
        }
    }
}
