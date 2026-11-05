package com.rita.ecommerceplatform.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** 107: the token is valid, but it does not allow this URL. */
@Component
class JsonAccessDeniedHandler(private val jsonMapper: JsonMapper) : AccessDeniedHandler {

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        val authorities = SecurityContextHolder.getContext().authentication?.authorities.orEmpty().map { it.authority }
        // Only on admin URLs: elsewhere (e.g. /api/customer/**) the real reason is the wrong role.
        val message = if (PASSWORD_CHANGE_REQUIRED in authorities && request.requestURI.startsWith("/api/admin/")) {
            "Password change required: call PATCH /api/admin/auth/password first"
        } else {
            "You do not have permission to access this resource"
        }
        writeResponse(jsonMapper, response, "107", message)
    }
}
