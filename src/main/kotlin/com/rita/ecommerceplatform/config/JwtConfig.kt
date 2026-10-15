package com.rita.ecommerceplatform.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * HS256 signing key from JWT_SECRET (base64, at least 256 bits).
 * The encoder signs tokens at login; the decoder verifies them (used from Step 8).
 */
@Configuration
class JwtConfig(
    @Value("\${app.jwt.secret}") secret: String,
    @Value("\${app.jwt.issuer}") private val issuer: String,
) {

    private val key: SecretKey = Base64.getDecoder().decode(secret).let { bytes ->
        require(bytes.size >= 32) { "JWT_SECRET must be at least 256 bits (32 bytes) once base64-decoded" }
        SecretKeySpec(bytes, "HmacSHA256")
    }

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))

    // Checks signature, expiry (exp, with 60s clock-skew allowance) and that iss is ours.
    @Bean
    fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
        setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer))
    }
}
