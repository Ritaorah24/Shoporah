package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.utility.SQL_NOW
import com.rita.ecommerceplatform.model.entity.CustomerAuth
import com.rita.ecommerceplatform.model.entity.CustomerAuthStatus
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

    fun updatePassword(customerId: Long, passwordHash: String): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_customer_auth
            SET customerauthpasswordhash = :passwordHash,
                customerauthpasswordchangedat = $SQL_NOW,
                customerauthupdatedat = $SQL_NOW
            WHERE customerauthcustomerid = :customerId
            """
        )
            .param("passwordHash", passwordHash)
            .param("customerId", customerId)
            .update()

    /**
     * Stores a new reset token hash valid for validMinutes, replacing any earlier one, unless a token was
     * issued less than cooldownSeconds ago (its expiry is still later than now + validMinutes - cooldown).
     * Returns 1 when stored, 0 during the cooldown; one statement, so two requests at once cannot both store.
     */
    fun saveResetToken(customerId: Long, tokenHash: String, validMinutes: Int, cooldownSeconds: Int): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_customer_auth
            SET customerauthresettokenhash = :tokenHash,
                customerauthresettokenexpiresat = $SQL_NOW + (:validMinutes * INTERVAL '1 minute'),
                customerauthupdatedat = $SQL_NOW
            WHERE customerauthcustomerid = :customerId
              AND (customerauthresettokenexpiresat IS NULL
                   OR customerauthresettokenexpiresat
                      <= $SQL_NOW + (:validMinutes * INTERVAL '1 minute') - (:cooldownSeconds * INTERVAL '1 second'))
            """
        )
            .param("tokenHash", tokenHash)
            .param("validMinutes", validMinutes)
            .param("cooldownSeconds", cooldownSeconds)
            .param("customerId", customerId)
            .update()

    /** The credentials whose reset token has this hash and has not expired yet. */
    fun findByValidResetTokenHash(tokenHash: String): CustomerAuth? =
        jdbcClient.sql(
            """
            SELECT * FROM public.ec_customer_auth
            WHERE customerauthresettokenhash = :tokenHash
              AND customerauthresettokenexpiresat > $SQL_NOW
            """
        )
            .param("tokenHash", tokenHash)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /**
     * Sets the new password and clears the token, only if the token is still the current, unexpired one.
     * Returns 1 when reset, 0 when the token was already used, replaced or expired (so it works only once).
     */
    fun resetPassword(tokenHash: String, passwordHash: String): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_customer_auth
            SET customerauthpasswordhash = :passwordHash,
                customerauthpasswordchangedat = $SQL_NOW,
                customerauthresettokenhash = NULL,
                customerauthresettokenexpiresat = NULL,
                customerauthfailedloginattempts = 0,
                customerauthlockeduntil = NULL,
                customerauthupdatedat = $SQL_NOW
            WHERE customerauthresettokenhash = :tokenHash
              AND customerauthresettokenexpiresat > $SQL_NOW
            """
        )
            .param("passwordHash", passwordHash)
            .param("tokenHash", tokenHash)
            .update()
}
