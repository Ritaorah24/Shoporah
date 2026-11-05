package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.config.JwtProps
import com.rita.ecommerceplatform.security.UserType
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class JwtService(
    private val jwtEncoder: JwtEncoder,
    private val jwtProps: JwtProps,
) {

    val expirySeconds: Long = jwtProps.expiryMinutes * 60

    /**
     * Signed access token. The payload is readable by anyone, so it holds IDs and roles only.
     * mustChangePassword is set for admin tokens only (D4); customer tokens leave it out.
     */
    fun createToken(subjectId: Long, userType: UserType, roles: List<String>, mustChangePassword: Boolean? = null): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer(jwtProps.issuer)
            .subject(subjectId.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(expirySeconds))
            .claim("userType", userType.name)
            .claim("roles", roles)
            .apply { if (mustChangePassword != null) claim("mustChangePassword", mustChangePassword) }
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }
}
