package com.rita.ecommerceplatform.security

import com.rita.ecommerceplatform.model.response.BaseResponse
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** 115: no token, or the token is expired, forged, malformed or from another issuer. */
@Component
class JsonAuthenticationEntryPoint(private val jsonMapper: JsonMapper) : AuthenticationEntryPoint {

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        // Standard hint telling the client which kind of credentials to send.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        writeResponse(jsonMapper, response, "115", "Missing, invalid or expired access token")
    }
}

/** The security handlers run in the filter chain before any controller, so they write the JSON themselves. */
internal fun writeResponse(jsonMapper: JsonMapper, response: HttpServletResponse, code: String, message: String) {
    response.status = HttpServletResponse.SC_OK
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.characterEncoding = Charsets.UTF_8.name()
    jsonMapper.writeValue(response.outputStream, BaseResponse(responseCode = code, responseMessage = message))
}
