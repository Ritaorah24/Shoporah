package com.rita.ecommerceplatform.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bound from application-dev.properties (local) and application.properties.
 * New key: add a field here, and add it to application-example.properties.
 */
@ConfigurationProperties(prefix = "datasource")
data class DatasourceProperties(
    val url: String,
    val username: String,
    val password: String,
    val driverClassName: String = "org.postgresql.Driver",
    val defaultAutoCommit: Boolean = true,
    val hikari: HikariProperties = HikariProperties(),
)

data class HikariProperties(
    val connectionTestQuery: String = "SELECT 1",
    val transactionIsolation: String = "TRANSACTION_READ_COMMITTED",
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
)

@ConfigurationProperties(prefix = "app")
data class AppProps(
    val stage: String = "dev",
)

@ConfigurationProperties(prefix = "jwt")
data class JwtProps(
    /** HS256 signing key: base64, at least 256 bits. */
    val secret: String,
    val issuer: String,
    val expiryMinutes: Long,
)

@ConfigurationProperties(prefix = "paystack")
data class PaystackProps(
    val secretKey: String,
    val baseUrl: String,
    /** Where Paystack sends the customer's browser after paying; blank means the one set on the Paystack dashboard. */
    val callbackUrl: String = "",
)

@ConfigurationProperties(prefix = "cloudinary")
data class CloudinaryProps(
    val baseUrl: String,
    val cloudName: String = "",
    val apiKey: String = "",
    val apiSecret: String = "",
)

@ConfigurationProperties(prefix = "notifications")
data class NotificationProps(
    val from: String,
    val batchSize: Int,
    val maxAttempts: Int,
    val retryDelaySeconds: Int,
)

@ConfigurationProperties(prefix = "password-reset")
data class PasswordResetProps(
    val tokenMinutes: Int,
    val cooldownSeconds: Int,
    val url: String,
)
