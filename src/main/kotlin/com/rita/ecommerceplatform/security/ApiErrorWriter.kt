package com.rita.ecommerceplatform.security

import com.rita.ecommerceplatform.exception.ApiError
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.json.JsonMapper

/**
 * Writes an ApiError as the response body. Used by the security handlers, which run
 * in the filter chain before any controller, so they write the JSON themselves.
 */
internal fun writeApiError(
    jsonMapper: JsonMapper,
    request: HttpServletRequest,
    response: HttpServletResponse,
    status: HttpStatus,
    message: String,
) {
    val body = ApiError(status = status.value(), error = status.reasonPhrase, message = message, path = request.requestURI)
    response.status = status.value()
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.characterEncoding = Charsets.UTF_8.name()
    jsonMapper.writeValue(response.outputStream, body)
}
