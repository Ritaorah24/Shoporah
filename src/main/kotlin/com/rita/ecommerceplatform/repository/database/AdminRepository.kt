package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.AdminQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.Admin
import com.rita.ecommerceplatform.model.entity.AdminStatus
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

interface AdminRepository {
    fun findByEmail(email: String): Admin?

    fun findById(adminId: Int): Admin?
}

@Repository
class AdminRepositoryImpl(private val jdbi: Jdbi) : AdminRepository {

    override fun findByEmail(email: String): Admin? =
        jdbi.withHandle<Admin?, Exception> { handle ->
            handle.createQuery(AdminQuery.FIND_BY_EMAIL)
                .bind("email", email)
                .mapTo<Admin>()
                .findOne()
                .orElse(null)
        }

    override fun findById(adminId: Int): Admin? =
        jdbi.withHandle<Admin?, Exception> { handle ->
            handle.createQuery(AdminQuery.FIND_BY_ID)
                .bind("adminId", adminId)
                .mapTo<Admin>()
                .findOne()
                .orElse(null)
        }
}
