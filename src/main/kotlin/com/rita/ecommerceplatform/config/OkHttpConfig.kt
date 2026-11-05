package com.rita.ecommerceplatform.config

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
class OkHttpConfig {

    @Bean
    fun okHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .addInterceptor(RedactingLoggingInterceptor())
            .retryOnConnectionFailure(true)
            .build()
    }
}

private class RedactingLoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val redactedUrl = redactUrl(request.url)

        klogger.info { "--> ${request.method} $redactedUrl" }

        val started = System.currentTimeMillis()
        val response = chain.proceed(request)   // original request, unmodified
        val ms = System.currentTimeMillis() - started

        klogger.info { "<-- ${response.code} ${response.message} $redactedUrl (${ms}ms)" }

        return response
    }

    private fun redactUrl(url: okhttp3.HttpUrl): String {
        val uuidRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        val redacted = url.newBuilder().apply {
            url.queryParameterNames
                .filter { it.lowercase() in SENSITIVE_PARAMS }
                .forEach { setQueryParameter(it, "******") }
        }.build().toString()

        return redacted.replace(uuidRegex, "******")
    }

    companion object {
        private val klogger = KotlinLogging.logger {}
        private val SENSITIVE_PARAMS = setOf(
            "authtoken", "apikey", "token", "secret", "password", "pin", "otp"
        )
    }
}
