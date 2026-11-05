package com.rita.ecommerceplatform.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProps(
    val stage: String = "dev",
)
