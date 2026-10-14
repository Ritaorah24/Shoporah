package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.Admin
import com.rita.ecommerceplatform.model.AdminStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class AdminRepository(private val jdbcClient: JdbcClient) {

    // Every admin query joins ec_role so the Admin carries its role name (needed for the JWT).
    private val selectWithRole = """
        SELECT a.*, r.rolename
        FROM public.ec_admin a
        JOIN public.ec_role r ON r.roleid = a.adminroleid
    """

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

    fun findByEmail(email: String): Admin? =
        jdbcClient.sql("$selectWithRole WHERE a.adminemail = :email")
            .param("email", email)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun findById(adminId: Int): Admin? =
        jdbcClient.sql("$selectWithRole WHERE a.adminid = :adminId")
            .param("adminId", adminId)
            .query(rowMapper)
            .optional()
            .orElse(null)
}
