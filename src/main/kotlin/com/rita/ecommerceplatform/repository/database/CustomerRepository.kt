package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.CustomerQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.containsPattern
import com.rita.ecommerceplatform.model.entity.Customer
import com.rita.ecommerceplatform.model.entity.CustomerStatus
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

interface CustomerRepository {
    fun existsByEmail(email: String): Boolean

    /** Inserts a customer and returns the generated customerid. Status and timestamps come from column defaults. */
    fun insert(firstName: String, lastName: String, email: String, phoneNumber: String?): Long

    fun findById(customerId: Long): Customer?

    /**
     * Locks the customer row until the transaction ends, so changes to one customer's data
     * (e.g. their addresses) run one at a time. Must be called inside @Transactional.
     */
    fun lockById(customerId: Long)

    fun findByEmail(email: String): Customer?

    /**
     * One page of customers for admins, newest first: only `status` when given, and only those whose email,
     * full name or phone number contains `contains` (any case). Reads ec_customer only, never the auth table.
     */
    fun search(status: CustomerStatus?, contains: String?, limit: Int, offset: Long): List<Customer>

    fun count(status: CustomerStatus?, contains: String?): Long

    /** Updates the editable profile fields (not email). Returns the number of rows updated (0 or 1). */
    fun updateProfile(customerId: Long, firstName: String, lastName: String, phoneNumber: String?): Int
}

@Repository
class CustomerRepositoryImpl(private val jdbi: Jdbi) : CustomerRepository {

    // Maps one ec_customer row (database column names) to a Customer (Kotlin names).
    override fun existsByEmail(email: String): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(CustomerQuery.EXISTS_BY_EMAIL)
                .bind("email", email)
                .mapTo<Boolean>()
                .one()
        }

    override fun insert(firstName: String, lastName: String, email: String, phoneNumber: String?): Long =
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(CustomerQuery.INSERT)
                .bind("firstName", firstName)
                .bind("lastName", lastName)
                .bind("email", email)
                .bind("phoneNumber", phoneNumber)
                .mapTo<Long>()
                .one()
        }

    override fun findById(customerId: Long): Customer? =
        jdbi.withHandle<Customer?, Exception> { handle ->
            handle.createQuery(CustomerQuery.FIND_BY_ID)
                .bind("customerId", customerId)
                .mapTo<Customer>()
                .findOne()
                .orElse(null)
        }

    override fun lockById(customerId: Long) {
        jdbi.withHandle<Any, Exception> { handle ->
            handle.createQuery(CustomerQuery.LOCK_BY_ID)
                .bind("customerId", customerId)
                .mapTo<Long>()
                .findOne()
        }
    }

    override fun findByEmail(email: String): Customer? =
        jdbi.withHandle<Customer?, Exception> { handle ->
            handle.createQuery(CustomerQuery.FIND_BY_EMAIL)
                .bind("email", email)
                .mapTo<Customer>()
                .findOne()
                .orElse(null)
        }

    override fun search(status: CustomerStatus?, contains: String?, limit: Int, offset: Long): List<Customer> {
        val (where, params) = searchWhere(status, contains)
        return jdbi.withHandle<List<Customer>, Exception> { handle ->
            handle.createQuery("""
            SELECT * FROM ${PrefixConstant.ENTITY}CUSTOMER
            $where
            ORDER BY customercreatedat DESC, customerid DESC
            LIMIT :limit OFFSET :offset
            """)
                .bindMap(params + mapOf("limit" to limit, "offset" to offset))
                .mapTo<Customer>()
                .list()
        }
    }

    override fun count(status: CustomerStatus?, contains: String?): Long {
        val (where, params) = searchWhere(status, contains)
        return jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM ${PrefixConstant.ENTITY}CUSTOMER $where")
                .bindMap(params)
                .mapTo<Long>()
                .one()
        }
    }

    // Fixed SQL fragments only; the values are named parameters.
    private fun searchWhere(status: CustomerStatus?, contains: String?): Pair<String, Map<String, Any>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        status?.let {
            conditions += "customerstatus = :status"
            params["status"] = it.name
        }
        contains?.let {
            conditions += """
                ((customeremail::TEXT) ILIKE :pattern ESCAPE '!'
                 OR (customerfirstname::TEXT || ' ' || customerlastname::TEXT) ILIKE :pattern ESCAPE '!'
                 OR (customerphonenumber::TEXT) ILIKE :pattern ESCAPE '!')
            """.trimIndent()
            params["pattern"] = containsPattern(it)
        }
        return (if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")) to params
    }

    override fun updateProfile(customerId: Long, firstName: String, lastName: String, phoneNumber: String?): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CustomerQuery.UPDATE_PROFILE)
                .bind("firstName", firstName)
                .bind("lastName", lastName)
                .bind("phoneNumber", phoneNumber)
                .bind("customerId", customerId)
                .execute()
        }
}
