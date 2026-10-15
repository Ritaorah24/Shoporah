package com.rita.ecommerceplatform.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** 401: no token, or the token is expired, forged, malformed or from another issuer. */
@Component
class JsonAuthenticationEntryPoint(private val jsonMapper: JsonMapper) : AuthenticationEntryPoint {

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        // Standard hint telling the client which kind of credentials to send.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        writeApiError(jsonMapper, request, response, HttpStatus.UNAUTHORIZED, "Missing, invalid or expired access token")
    }
}
