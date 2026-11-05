package com.rita.ecommerceplatform.repository.api.paystack

import com.rita.ecommerceplatform.config.PaystackProps
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
    private val paystackProps: PaystackProps,
) {

    init {
        require(paystackProps.secretKey.isNotBlank()) { "paystack.secret-key must be set" }
    }

    // A plain RestClient.builder(): bodies are sent and read as JSON strings, so Boot's preconfigured
    // builder (a separate starter in Boot 4) is not needed.
    @Bean
    fun paystackRestClient(): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(10)) }
        return RestClient.builder()
            .baseUrl(paystackProps.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${paystackProps.secretKey}")
            .requestFactory(requestFactory)
            .build()
    }
}
