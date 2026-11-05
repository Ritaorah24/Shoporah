package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.utility.SQL_NOW
import com.rita.ecommerceplatform.model.entity.AdminAuth
import com.rita.ecommerceplatform.model.entity.AdminAuthStatus
import com.rita.ecommerceplatform.model.entity.PasswordStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class AdminAuthRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        AdminAuth(
            adminAuthId = rs.getInt("adminauthid"),
            adminId = rs.getInt("adminauthadminid"),
            passwordHash = rs.getString("adminauthpasswordhash"),
            passwordStatus = PasswordStatus.valueOf(rs.getString("adminauthpasswordstatus").uppercase()),
            passwordChangedAt = rs.getObject("adminauthpasswordchangedat", LocalDateTime::class.java),
            failedLoginAttempts = rs.getInt("adminauthfailedloginattempts"),
            lastFailedLoginAt = rs.getObject("adminauthlastfailedloginat", LocalDateTime::class.java),
            lockedUntil = rs.getObject("adminauthlockeduntil", LocalDateTime::class.java),
            lastLoginAt = rs.getObject("adminauthlastloginat", LocalDateTime::class.java),
            resetTokenHash = rs.getString("adminauthresettokenhash"),
            resetTokenExpiresAt = rs.getObject("adminauthresettokenexpiresat", LocalDateTime::class.java),
            status = AdminAuthStatus.valueOf(rs.getString("adminauthstatus").uppercase()),
            createdAt = rs.getObject("adminauthcreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("adminauthupdatedat", LocalDateTime::class.java),
        )
    }

    fun findByAdminId(adminId: Int): AdminAuth? =
        jdbcClient.sql("SELECT * FROM public.ec_admin_auth WHERE adminauthadminid = :adminId")
            .param("adminId", adminId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun updateLastLoginAt(adminId: Int): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_admin_auth
            SET adminauthlastloginat = $SQL_NOW,
                adminauthupdatedat = $SQL_NOW
            WHERE adminauthadminid = :adminId
            """
        )
            .param("adminId", adminId)
            .update()

    /** Stores a new password hash and clears MUST_CHANGE (password status back to ACTIVE). */
    fun updatePassword(adminId: Int, passwordHash: String): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_admin_auth
            SET adminauthpasswordhash = :passwordHash,
                adminauthpasswordstatus = :passwordStatus,
                adminauthpasswordchangedat = $SQL_NOW,
                adminauthupdatedat = $SQL_NOW
            WHERE adminauthadminid = :adminId
            """
        )
            .param("passwordHash", passwordHash)
            .param("passwordStatus", PasswordStatus.ACTIVE.name)
            .param("adminId", adminId)
            .update()
}
