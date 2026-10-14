package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.CustomerAuth
import com.rita.ecommerceplatform.model.CustomerAuthStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class CustomerAuthRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        CustomerAuth(
            customerAuthId = rs.getLong("customerauthid"),
            customerId = rs.getLong("customerauthcustomerid"),
            passwordHash = rs.getString("customerauthpasswordhash"),
            passwordChangedAt = rs.getObject("customerauthpasswordchangedat", LocalDateTime::class.java),
            failedLoginAttempts = rs.getInt("customerauthfailedloginattempts"),
            lastFailedLoginAt = rs.getObject("customerauthlastfailedloginat", LocalDateTime::class.java),
            lockedUntil = rs.getObject("customerauthlockeduntil", LocalDateTime::class.java),
            lastLoginAt = rs.getObject("customerauthlastloginat", LocalDateTime::class.java),
            resetTokenHash = rs.getString("customerauthresettokenhash"),
            resetTokenExpiresAt = rs.getObject("customerauthresettokenexpiresat", LocalDateTime::class.java),
            status = CustomerAuthStatus.valueOf(rs.getString("customerauthstatus").uppercase()),
            createdAt = rs.getObject("customerauthcreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("customerauthupdatedat", LocalDateTime::class.java),
        )
    }

    /** Inserts the credentials row for a new customer and returns the generated customerauthid. */
    fun insert(customerId: Long, passwordHash: String): Long =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_customer_auth (customerauthcustomerid, customerauthpasswordhash)
            VALUES (:customerId, :passwordHash)
            RETURNING customerauthid
            """
        )
            .param("customerId", customerId)
            .param("passwordHash", passwordHash)
            .query(Long::class.javaObjectType)
            .single()

    fun findByCustomerId(customerId: Long): CustomerAuth? =
        jdbcClient.sql("SELECT * FROM public.ec_customer_auth WHERE customerauthcustomerid = :customerId")
            .param("customerId", customerId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun updateLastLoginAt(customerId: Long): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_customer_auth
            SET customerauthlastloginat = $SQL_NOW,
                customerauthupdatedat = $SQL_NOW
            WHERE customerauthcustomerid = :customerId
            """
        )
            .param("customerId", customerId)
            .update()
}
