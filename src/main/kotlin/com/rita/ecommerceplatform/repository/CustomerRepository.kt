package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.Customer
import com.rita.ecommerceplatform.model.CustomerStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class CustomerRepository(private val jdbcClient: JdbcClient) {

    // Maps one ec_customer row (database column names) to a Customer (Kotlin names).
    private val rowMapper = RowMapper { rs, _ ->
        Customer(
            customerId = rs.getLong("customerid"),
            firstName = rs.getString("customerfirstname"),
            lastName = rs.getString("customerlastname"),
            email = rs.getString("customeremail"),
            phoneNumber = rs.getString("customerphonenumber"),
            status = CustomerStatus.valueOf(rs.getString("customerstatus").uppercase()),
            createdAt = rs.getObject("customercreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("customerupdatedat", LocalDateTime::class.java),
        )
    }

    fun existsByEmail(email: String): Boolean =
        jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM public.ec_customer WHERE customeremail = :email)")
            .param("email", email)
            .query(Boolean::class.javaObjectType)
            .single()

    /** Inserts a customer and returns the generated customerid. Status and timestamps come from column defaults. */
    fun insert(firstName: String, lastName: String, email: String, phoneNumber: String?): Long =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_customer (customerfirstname, customerlastname, customeremail, customerphonenumber)
            VALUES (:firstName, :lastName, :email, :phoneNumber)
            RETURNING customerid
            """
        )
            .param("firstName", firstName)
            .param("lastName", lastName)
            .param("email", email)
            .param("phoneNumber", phoneNumber)
            .query(Long::class.javaObjectType)
            .single()

    fun findById(customerId: Long): Customer? =
        jdbcClient.sql("SELECT * FROM public.ec_customer WHERE customerid = :customerId")
            .param("customerId", customerId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /**
     * Locks the customer row until the transaction ends, so changes to one customer's data
     * (e.g. their addresses) run one at a time. Must be called inside @Transactional.
     */
    fun lockById(customerId: Long) {
        jdbcClient.sql("SELECT customerid FROM public.ec_customer WHERE customerid = :customerId FOR UPDATE")
            .param("customerId", customerId)
            .query(Long::class.javaObjectType)
            .optional()
    }

    fun findByEmail(email: String): Customer? =
        jdbcClient.sql("SELECT * FROM public.ec_customer WHERE customeremail = :email")
            .param("email", email)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** Updates the editable profile fields (not email). Returns the number of rows updated (0 or 1). */
    fun updateProfile(customerId: Long, firstName: String, lastName: String, phoneNumber: String?): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_customer
            SET customerfirstname = :firstName,
                customerlastname = :lastName,
                customerphonenumber = :phoneNumber,
                customerupdatedat = $SQL_NOW
            WHERE customerid = :customerId
            """
        )
            .param("firstName", firstName)
            .param("lastName", lastName)
            .param("phoneNumber", phoneNumber)
            .param("customerId", customerId)
            .update()
}
