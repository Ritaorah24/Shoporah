package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/** One row of public.ec_customer_auth (1:1 with ec_customer). Holds hashes only. */
data class CustomerAuth(
    var customerAuthId: Long? = null,
    var customerAuthCustomerId: Long? = null,
    var customerAuthPasswordHash: String? = null,
    var customerAuthPasswordChangedAt: LocalDateTime? = null,
    var customerAuthFailedLoginAttempts: Int? = null,
    var customerAuthLastFailedLoginAt: LocalDateTime? = null,
    var customerAuthLockedUntil: LocalDateTime? = null,
    var customerAuthLastLoginAt: LocalDateTime? = null,
    var customerAuthResetTokenHash: String? = null,
    var customerAuthResetTokenExpiresAt: LocalDateTime? = null,
    var customerAuthStatus: CustomerAuthStatus? = null,
    var customerAuthCreatedAt: LocalDateTime? = null,
    var customerAuthUpdatedAt: LocalDateTime? = null,
)

/** Allowed values of ec_customer_auth.customerauthstatus (ck__ec_customerauth__status). */
enum class CustomerAuthStatus { ACTIVE, LOCKED, DISABLED, DELETED }
