package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.AdminAuthQuery
import org.jdbi.v3.core.Jdbi
import com.rita.ecommerceplatform.model.entity.AdminAuth
import com.rita.ecommerceplatform.model.entity.AdminAuthStatus
import com.rita.ecommerceplatform.model.entity.PasswordStatus
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

interface AdminAuthRepository {
    fun findByAdminId(adminId: Int): AdminAuth?

    fun updateLastLoginAt(adminId: Int): Int

    /** Stores a new password hash and clears MUST_CHANGE (password status back to ACTIVE). */
    fun updatePassword(adminId: Int, passwordHash: String): Int
}

@Repository
class AdminAuthRepositoryImpl(private val jdbi: Jdbi) : AdminAuthRepository {

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

    override fun findByAdminId(adminId: Int): AdminAuth? =
        jdbi.withHandle<AdminAuth?, Exception> { handle ->
            handle.createQuery(AdminAuthQuery.FIND_BY_ADMIN_ID)
                .bind("adminId", adminId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun updateLastLoginAt(adminId: Int): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(AdminAuthQuery.UPDATE_LAST_LOGIN_AT)
                .bind("adminId", adminId)
                .execute()
        }

    override fun updatePassword(adminId: Int, passwordHash: String): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(AdminAuthQuery.UPDATE_PASSWORD)
                .bind("passwordHash", passwordHash)
                .bind("passwordStatus", PasswordStatus.ACTIVE.name)
                .bind("adminId", adminId)
                .execute()
        }
}
