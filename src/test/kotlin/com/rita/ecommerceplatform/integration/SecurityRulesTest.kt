package com.rita.ecommerceplatform.integration

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/**
 * Token checks done by the JWT decoder. Bad tokens are signed with the app's own encoder
 * (except the forged one), so these tests prove the decoder rejects the claims, not just a bad key.
 */
class SecurityRulesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jwtEncoder: JwtEncoder

    private fun customerToken(
        customerId: Long,
        issuer: String = "ecommerce-platform",
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant = issuedAt.plusSeconds(600),
        encoder: JwtEncoder = jwtEncoder,
    ): String {
        val claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(customerId.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("userType", "CUSTOMER")
            .claim("roles", listOf("CUSTOMER"))
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    private fun expectRejected(token: String) {
        mockMvc.get("/api/customer/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", containsString("Bearer")) }
            jsonPath("$.message") { value("Missing, invalid or expired access token") }
        }
    }

    @Test
    fun `a correctly built token is accepted`() {
        // Control case: shows the helper builds valid tokens, so the rejections below are real.
        val customerId = registerCustomer()
        mockMvc.get("/api/customer/me") {
            header("Authorization", "Bearer ${customerToken(customerId)}")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `an expired token returns 401`() {
        val customerId = registerCustomer()
        val issuedAt = Instant.now().minusSeconds(7200)
        // Expired an hour ago: well past the decoder's 60 second clock-skew allowance.
        expectRejected(customerToken(customerId, issuedAt = issuedAt, expiresAt = issuedAt.plusSeconds(3600)))
    }

    @Test
    fun `a token from another issuer returns 401`() {
        expectRejected(customerToken(registerCustomer(), issuer = "someone-else"))
    }

    @Test
    fun `a token signed with another key returns 401`() {
        val otherKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val forger = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(SecretKeySpec(otherKey, "HmacSHA256")))
        expectRejected(customerToken(registerCustomer(), encoder = forger))
    }

    @Test
    fun `a token whose payload was edited returns 401`() {
        val (header, _, signature) = customerToken(registerCustomer()).split(".")
        // Same signature, different subject: the signature no longer matches the payload.
        val editedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            json(
                "iss" to "ecommerce-platform",
                "sub" to "1",
                "userType" to "CUSTOMER",
                "roles" to listOf("CUSTOMER"),
                "exp" to Instant.now().plusSeconds(600).epochSecond,
            ).toByteArray(),
        )
        expectRejected("$header.$editedPayload.$signature")
    }

    @Test
    fun `an invalid token is rejected even on the login endpoint`() {
        // Documents the Postman trap: an old Bearer header makes even permitAll endpoints return 401.
        mockMvc.post("/api/auth/login") {
            header("Authorization", "Bearer not-a-real-token")
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to uniqueEmail(), "password" to DEFAULT_PASSWORD)
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.message") { value("Missing, invalid or expired access token") }
        }
    }
}
