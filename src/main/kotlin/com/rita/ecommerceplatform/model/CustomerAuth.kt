package com.rita.ecommerceplatform.model

import java.time.LocalDateTime

/** One row of public.ec_customer_auth (1:1 with ec_customer). Holds hashes only. */
data class CustomerAuth(
    val customerAuthId: Long,
    val customerId: Long,
    val passwordHash: String,
    val passwordChangedAt: LocalDateTime?,
    val failedLoginAttempts: Int,
    val lastFailedLoginAt: LocalDateTime?,
    val lockedUntil: LocalDateTime?,
    val lastLoginAt: LocalDateTime?,
    val resetTokenHash: String?,
    val resetTokenExpiresAt: LocalDateTime?,
    val status: CustomerAuthStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Allowed values of ec_customer_auth.customerauthstatus (ck__ec_customerauth__status). */
enum class CustomerAuthStatus { ACTIVE, LOCKED, DISABLED, DELETED }
