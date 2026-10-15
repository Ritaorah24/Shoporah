package com.rita.ecommerceplatform.security

import org.springframework.security.oauth2.jwt.Jwt

/**
 * Who is calling, taken from the verified token. Controllers use this instead of
 * any ID in the URL or body, so a caller can only act as themselves.
 */
data class CurrentUser(val id: Long, val userType: UserType) {
    companion object {
        // Both claims are always set by JwtService, and the signature proves the token is ours,
        // so a missing claim means a bug, not bad user input.
        fun from(jwt: Jwt) = CurrentUser(
            id = checkNotNull(jwt.subject) { "Token has no sub claim" }.toLong(),
            userType = UserType.valueOf(checkNotNull(jwt.getClaimAsString("userType")) { "Token has no userType claim" }),
        )
    }
}
