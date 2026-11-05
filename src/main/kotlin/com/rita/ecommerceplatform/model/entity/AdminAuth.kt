package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/** One row of public.ec_admin_auth (1:1 with ec_admin). Holds hashes only. */
data class AdminAuth(
    var adminAuthId: Int? = null,
    var adminAuthAdminId: Int? = null,
    var adminAuthPasswordHash: String? = null,
    var adminAuthPasswordStatus: PasswordStatus? = null,
    var adminAuthPasswordChangedAt: LocalDateTime? = null,
    var adminAuthFailedLoginAttempts: Int? = null,
    var adminAuthLastFailedLoginAt: LocalDateTime? = null,
    var adminAuthLockedUntil: LocalDateTime? = null,
    var adminAuthLastLoginAt: LocalDateTime? = null,
    var adminAuthResetTokenHash: String? = null,
    var adminAuthResetTokenExpiresAt: LocalDateTime? = null,
    var adminAuthStatus: AdminAuthStatus? = null,
    var adminAuthCreatedAt: LocalDateTime? = null,
    var adminAuthUpdatedAt: LocalDateTime? = null,
)

/** Allowed values of ec_admin_auth.adminauthpasswordstatus (ck__ec_adminauth__passwordstatus). */
enum class PasswordStatus { ACTIVE, MUST_CHANGE }

/** Allowed values of ec_admin_auth.adminauthstatus (ck__ec_adminauth__status). */
enum class AdminAuthStatus { ACTIVE, LOCKED, DISABLED }
