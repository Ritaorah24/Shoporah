package com.rita.ecommerceplatform.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(
    AppProps::class,
)
class AppBeansConfig
