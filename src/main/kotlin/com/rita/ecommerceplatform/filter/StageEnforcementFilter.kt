package com.rita.ecommerceplatform.filter

import com.rita.ecommerceplatform.config.AppProps
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class StageEnforcementFilter(
    private val appProps: AppProps,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return EXCLUDED_PATHS.any { path.startsWith(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestedStage = request.requestURI.trim('/').substringBefore('/')

        if (requestedStage != appProps.stage) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            response.contentType = "application/json"
            response.writer.write(
                """{"responseCode":"106","responseMessage":"Invalid stage."}"""
            )
            return
        }

        filterChain.doFilter(request, response)
    }

    private companion object {
        // Routes with no {stage} prefix, e.g. a webhook URL registered with a provider.
        val EXCLUDED_PATHS = listOf(
            "/swagger-ui", "/v3/api-docs", "/docs", "/webhook"
        )
    }
}
