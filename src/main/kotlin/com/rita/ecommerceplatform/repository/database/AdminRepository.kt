package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.AdminQuery
import org.jdbi.v3.core.Jdbi
import com.rita.ecommerceplatform.model.entity.Admin
import com.rita.ecommerceplatform.model.entity.AdminStatus
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

interface AdminRepository {
    fun findByEmail(email: String): Admin?

    fun findById(adminId: Int): Admin?
}

@Repository
class AdminRepositoryImpl(private val jdbi: Jdbi) : AdminRepository {

    private val rowMapper = RowMapper { rs, _ ->
        Admin(
            adminId = rs.getInt("adminid"),
            roleId = rs.getInt("adminroleid"),
            roleName = rs.getString("rolename"),
            firstName = rs.getString("adminfirstname"),
            lastName = rs.getString("adminlastname"),
            email = rs.getString("adminemail"),
            phoneNumber = rs.getString("adminphonenumber"),
            status = AdminStatus.valueOf(rs.getString("adminstatus").uppercase()),
            createdAt = rs.getObject("admincreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("adminupdatedat", LocalDateTime::class.java),
        )
    }

    override fun findByEmail(email: String): Admin? =
        jdbi.withHandle<Admin?, Exception> { handle ->
            handle.createQuery(AdminQuery.FIND_BY_EMAIL)
                .bind("email", email)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun findById(adminId: Int): Admin? =
        jdbi.withHandle<Admin?, Exception> { handle ->
            handle.createQuery(AdminQuery.FIND_BY_ID)
                .bind("adminId", adminId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }
}
