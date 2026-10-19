package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.AddressDetails
import com.rita.ecommerceplatform.model.CustomerAddress
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * Every query is scoped to the owning customer (customerId in the WHERE clause), so one customer
 * can never read or change another customer's address, even with a guessed addressId.
 */
@Repository
class CustomerAddressRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        CustomerAddress(
            addressId = rs.getLong("customeraddressid"),
            customerId = rs.getLong("customeraddresscustomerid"),
            details = AddressDetails(
                recipientName = rs.getString("customeraddressrecipientname"),
                recipientPhoneNumber = rs.getString("customeraddressrecipientphonenumber"),
                addressLine1 = rs.getString("customeraddressline1"),
                addressLine2 = rs.getString("customeraddressline2"),
                city = rs.getString("customeraddresscity"),
                state = rs.getString("customeraddressstate"),
                country = rs.getString("customeraddresscountry"),
                postalCode = rs.getString("customeraddresspostalcode"),
            ),
            isDefault = rs.getBoolean("customeraddressisdefault"),
            createdAt = rs.getObject("customeraddresscreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("customeraddressupdatedat", LocalDateTime::class.java),
        )
    }

    /** The customer's addresses, default first, then newest first. */
    fun findByCustomer(customerId: Long): List<CustomerAddress> =
        jdbcClient.sql(
            """
            SELECT * FROM public.ec_customer_address
            WHERE customeraddresscustomerid = :customerId
            ORDER BY customeraddressisdefault DESC, customeraddresscreatedat DESC, customeraddressid DESC
            """
        )
            .param("customerId", customerId)
            .query(rowMapper)
            .list()

    fun findById(addressId: Long, customerId: Long): CustomerAddress? =
        jdbcClient.sql(
            "SELECT * FROM public.ec_customer_address WHERE customeraddressid = :addressId AND customeraddresscustomerid = :customerId"
        )
            .param("addressId", addressId)
            .param("customerId", customerId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun findDefault(customerId: Long): CustomerAddress? =
        jdbcClient.sql(
            "SELECT * FROM public.ec_customer_address WHERE customeraddresscustomerid = :customerId AND customeraddressisdefault"
        )
            .param("customerId", customerId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun countByCustomer(customerId: Long): Long =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_customer_address WHERE customeraddresscustomerid = :customerId")
            .param("customerId", customerId)
            .query(Long::class.javaObjectType)
            .single()

    /** Inserts an address and returns the generated customeraddressid. */
    fun insert(customerId: Long, details: AddressDetails, isDefault: Boolean): Long =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_customer_address (
                customeraddresscustomerid, customeraddressrecipientname, customeraddressrecipientphonenumber,
                customeraddressline1, customeraddressline2, customeraddresscity, customeraddressstate,
                customeraddresscountry, customeraddresspostalcode, customeraddressisdefault
            )
            VALUES (
                :customerId, :recipientName, :recipientPhoneNumber,
                :addressLine1, :addressLine2, :city, :state,
                :country, :postalCode, :isDefault
            )
            RETURNING customeraddressid
            """
        )
            .param("customerId", customerId)
            .params(detailParams(details))
            .param("isDefault", isDefault)
            .query(Long::class.javaObjectType)
            .single()

    /** Replaces the delivery details (the service merges a partial PATCH first). Returns rows updated (0 or 1). */
    fun update(addressId: Long, customerId: Long, details: AddressDetails): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_customer_address
            SET customeraddressrecipientname = :recipientName,
                customeraddressrecipientphonenumber = :recipientPhoneNumber,
                customeraddressline1 = :addressLine1,
                customeraddressline2 = :addressLine2,
                customeraddresscity = :city,
                customeraddressstate = :state,
                customeraddresscountry = :country,
                customeraddresspostalcode = :postalCode,
                customeraddressupdatedat = $SQL_NOW
            WHERE customeraddressid = :addressId AND customeraddresscustomerid = :customerId
            """
        )
            .params(detailParams(details))
            .param("addressId", addressId)
            .param("customerId", customerId)
            .update()

    /**
     * Removes the default flag from the customer's current default, if any. Must run before
     * markDefault in the same transaction: uq__ec_customeraddress__default allows only one default.
     */
    fun clearDefault(customerId: Long): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_customer_address
            SET customeraddressisdefault = FALSE,
                customeraddressupdatedat = $SQL_NOW
            WHERE customeraddresscustomerid = :customerId AND customeraddressisdefault
            """
        )
            .param("customerId", customerId)
            .update()

    /** Makes this address the default. Returns rows updated (0 or 1). */
    fun markDefault(addressId: Long, customerId: Long): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_customer_address
            SET customeraddressisdefault = TRUE,
                customeraddressupdatedat = $SQL_NOW
            WHERE customeraddressid = :addressId AND customeraddresscustomerid = :customerId
            """
        )
            .param("addressId", addressId)
            .param("customerId", customerId)
            .update()

    /** Hard delete. Orders keep their own copy of the address, and ordercustomeraddressid becomes NULL. Returns rows deleted. */
    fun delete(addressId: Long, customerId: Long): Int =
        jdbcClient.sql(
            "DELETE FROM public.ec_customer_address WHERE customeraddressid = :addressId AND customeraddresscustomerid = :customerId"
        )
            .param("addressId", addressId)
            .param("customerId", customerId)
            .update()

    private fun detailParams(details: AddressDetails): Map<String, Any?> = mapOf(
        "recipientName" to details.recipientName,
        "recipientPhoneNumber" to details.recipientPhoneNumber,
        "addressLine1" to details.addressLine1,
        "addressLine2" to details.addressLine2,
        "city" to details.city,
        "state" to details.state,
        "country" to details.country,
        "postalCode" to details.postalCode,
    )
}
