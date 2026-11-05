package com.rita.ecommerceplatform.filter

import com.rita.ecommerceplatform.utility.LogRedactor
import com.rita.ecommerceplatform.utility.constant.SensitiveDetails
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.ByteArrayInputStream

private val klogger = KotlinLogging.logger {}

/** Logs each request and response with sensitive fields masked. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class RequestResponseLoggingFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return EXCLUDED_PATHS.any { path.startsWith(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val ipAddress = request.remoteAddr
        val method = request.method
        val path = request.requestURI

        klogger.info { "Incoming Request method=$method path=$path ip=$ipAddress headers=${maskedHeaders(request)}" }

        // Uploads are never buffered, and Spring reads form posts as parameters.
        val contentType = request.contentType.orEmpty().lowercase()
        val cachedRequest = if (contentType.startsWith("multipart/") || contentType.startsWith("application/x-www-form-urlencoded")) null
                            else CachedBodyRequest(request)
        val maskedBody = cachedRequest?.let { LogRedactor.body(it.bodyText()) } ?: "[body omitted]"
        if (maskedBody.isNotBlank()) {
            klogger.info { "[MAIN_REQUEST:::::][$maskedBody]" }
        }

        val wrappedResponse = ContentCachingResponseWrapper(response)
        val started = System.currentTimeMillis()

        try {
            filterChain.doFilter(cachedRequest ?: request, wrappedResponse)
        } finally {
            val durationMs = (System.currentTimeMillis() - started).toInt()
            klogger.info { "Outgoing Response status=${wrappedResponse.status} durationMs=$durationMs" }

            val responseBody = String(wrappedResponse.contentAsByteArray, Charsets.UTF_8)
            wrappedResponse.copyBodyToResponse()

            val maskedResponseBody = LogRedactor.body(responseBody)
            if (maskedResponseBody.isNotBlank()) {
                klogger.info { "[MAIN_RESPONSE:::::][${maskedResponseBody.take(1000)}]" }
            }
        }
    }

    private fun maskedHeaders(request: HttpServletRequest): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val names = request.headerNames ?: return out
        for (name in names.toList()) {
            val value = request.getHeader(name) ?: continue
            out[name] = if (name.lowercase() in SensitiveDetails.SENSITIVE_HEADERS) "******" else value
        }
        return out
    }

    private companion object {
        // Paystack's webhook bodies carry customer details and are not logged.
        val EXCLUDED_PATHS = listOf("/swagger-ui", "/docs", "/v3/api-docs", "/webhook")
    }
}

/** Reads the body once so the logger and the controller can both see it. */
private class CachedBodyRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
    private val body: ByteArray = request.inputStream.readBytes()

    fun bodyText(): String = String(body, Charsets.UTF_8)

    override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
        private val delegate = ByteArrayInputStream(body)
        override fun read(): Int = delegate.read()
        override fun isFinished(): Boolean = delegate.available() == 0
        override fun isReady(): Boolean = true
        override fun setReadListener(listener: ReadListener?) = Unit
    }

    override fun getReader() = inputStream.bufferedReader(Charsets.UTF_8)
}
