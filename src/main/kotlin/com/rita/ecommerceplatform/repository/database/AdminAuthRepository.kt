package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.AdminAuthQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.AdminAuth
import com.rita.ecommerceplatform.model.entity.AdminAuthStatus
import com.rita.ecommerceplatform.model.entity.PasswordStatus
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

    override fun findByAdminId(adminId: Int): AdminAuth? =
        jdbi.withHandle<AdminAuth?, Exception> { handle ->
            handle.createQuery(AdminAuthQuery.FIND_BY_ADMIN_ID)
                .bind("adminId", adminId)
                .mapTo<AdminAuth>()
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
