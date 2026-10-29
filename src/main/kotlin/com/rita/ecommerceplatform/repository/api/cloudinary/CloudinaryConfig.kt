package com.rita.ecommerceplatform.repository.api.cloudinary

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/** The Cloudinary account (Cloudinary dashboard → API Keys). All optional: without them, uploads answer 503. */
data class CloudinaryProperties(val cloudName: String, val apiKey: String, val apiSecret: String) {
    val isConfigured: Boolean get() = cloudName.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()
}

/**
 * The HTTP client used by CloudinaryImageStorage: Cloudinary's base URL and timeouts (5 s to connect,
 * 30 s to answer: an upload of up to 5 MB can take longer than a Paystack call). Requests are signed per
 * call, so no key is sent as a header.
 */
@Configuration
class CloudinaryConfig(
    @Value("\${app.cloudinary.base-url}") private val baseUrl: String,
) {

    @Bean
    fun cloudinaryProperties(
        @Value("\${app.cloudinary.cloud-name:}") cloudName: String,
        @Value("\${app.cloudinary.api-key:}") apiKey: String,
        @Value("\${app.cloudinary.api-secret:}") apiSecret: String,
    ) = CloudinaryProperties(cloudName.trim(), apiKey.trim(), apiSecret.trim())

    @Bean
    fun cloudinaryRestClient(): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(30)) }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build()
    }
}
