package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/** One row of public.ec_admin_auth (1:1 with ec_admin). Holds hashes only. */
data class AdminAuth(
    val adminAuthId: Int,
    val adminId: Int,
    val passwordHash: String?,
    val passwordStatus: PasswordStatus,
    val passwordChangedAt: LocalDateTime?,
    val failedLoginAttempts: Int,
    val lastFailedLoginAt: LocalDateTime?,
    val lockedUntil: LocalDateTime?,
    val lastLoginAt: LocalDateTime?,
    val resetTokenHash: String?,
    val resetTokenExpiresAt: LocalDateTime?,
    val status: AdminAuthStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Allowed values of ec_admin_auth.adminauthpasswordstatus (ck__ec_adminauth__passwordstatus). */
enum class PasswordStatus { ACTIVE, MUST_CHANGE }

/** Allowed values of ec_admin_auth.adminauthstatus (ck__ec_adminauth__status). */
enum class AdminAuthStatus { ACTIVE, LOCKED, DISABLED }
