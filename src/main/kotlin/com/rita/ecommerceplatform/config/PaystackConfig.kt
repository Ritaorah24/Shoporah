package com.rita.ecommerceplatform.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * The HTTP client used by PaystackGateway: Paystack's base URL, our secret key on every request,
 * and timeouts so a slow Paystack cannot hold our request threads (5 s to connect, 10 s to answer).
 */
@Configuration
class PaystackConfig(
    @Value("\${app.paystack.secret-key}") private val secretKey: String,
    @Value("\${app.paystack.base-url}") private val baseUrl: String,
) {

    init {
        require(secretKey.isNotBlank()) { "PAYSTACK_SECRET_KEY must be set" }
    }

    // A plain RestClient.builder(): bodies are sent and read as JSON strings, so Boot's preconfigured
    // builder (a separate starter in Boot 4) is not needed.
    @Bean
    fun paystackRestClient(): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(10)) }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $secretKey")
            .requestFactory(requestFactory)
            .build()
    }
}
