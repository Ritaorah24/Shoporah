package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.CustomerAuthQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.CustomerAuth
import com.rita.ecommerceplatform.model.entity.CustomerAuthStatus
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

interface CustomerAuthRepository {
    /** Inserts the credentials row for a new customer and returns the generated customerauthid. */
    fun insert(customerId: Long, passwordHash: String): Long

    fun findByCustomerId(customerId: Long): CustomerAuth?

    fun updateLastLoginAt(customerId: Long): Int

    fun updatePassword(customerId: Long, passwordHash: String): Int

    /**
     * Stores a new reset token hash valid for validMinutes, replacing any earlier one, unless a token was
     * issued less than cooldownSeconds ago (its expiry is still later than now + validMinutes - cooldown).
     * Returns 1 when stored, 0 during the cooldown; one statement, so two requests at once cannot both store.
     */
    fun saveResetToken(customerId: Long, tokenHash: String, validMinutes: Int, cooldownSeconds: Int): Int

    /** The credentials whose reset token has this hash and has not expired yet. */
    fun findByValidResetTokenHash(tokenHash: String): CustomerAuth?

    /**
     * Sets the new password and clears the token, only if the token is still the current, unexpired one.
     * Returns 1 when reset, 0 when the token was already used, replaced or expired (so it works only once).
     */
    fun resetPassword(tokenHash: String, passwordHash: String): Int
}

@Repository
class CustomerAuthRepositoryImpl(private val jdbi: Jdbi) : CustomerAuthRepository {

    override fun insert(customerId: Long, passwordHash: String): Long =
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(CustomerAuthQuery.INSERT)
                .bind("customerId", customerId)
                .bind("passwordHash", passwordHash)
                .mapTo<Long>()
                .one()
        }

    override fun findByCustomerId(customerId: Long): CustomerAuth? =
        jdbi.withHandle<CustomerAuth?, Exception> { handle ->
            handle.createQuery(CustomerAuthQuery.FIND_BY_CUSTOMER_ID)
                .bind("customerId", customerId)
                .mapTo<CustomerAuth>()
                .findOne()
                .orElse(null)
        }

    override fun updateLastLoginAt(customerId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CustomerAuthQuery.UPDATE_LAST_LOGIN_AT)
                .bind("customerId", customerId)
                .execute()
        }

    override fun updatePassword(customerId: Long, passwordHash: String): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CustomerAuthQuery.UPDATE_PASSWORD)
                .bind("passwordHash", passwordHash)
                .bind("customerId", customerId)
                .execute()
        }

    override fun saveResetToken(customerId: Long, tokenHash: String, validMinutes: Int, cooldownSeconds: Int): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CustomerAuthQuery.SAVE_RESET_TOKEN)
                .bind("tokenHash", tokenHash)
                .bind("validMinutes", validMinutes)
                .bind("cooldownSeconds", cooldownSeconds)
                .bind("customerId", customerId)
                .execute()
        }

    override fun findByValidResetTokenHash(tokenHash: String): CustomerAuth? =
        jdbi.withHandle<CustomerAuth?, Exception> { handle ->
            handle.createQuery(CustomerAuthQuery.FIND_BY_VALID_RESET_TOKEN_HASH)
                .bind("tokenHash", tokenHash)
                .mapTo<CustomerAuth>()
                .findOne()
                .orElse(null)
        }

    override fun resetPassword(tokenHash: String, passwordHash: String): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CustomerAuthQuery.RESET_PASSWORD)
                .bind("passwordHash", passwordHash)
                .bind("tokenHash", tokenHash)
                .execute()
        }
}
