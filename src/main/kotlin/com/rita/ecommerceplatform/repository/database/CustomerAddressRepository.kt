package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.CustomerAddressQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.AddressDetails
import com.rita.ecommerceplatform.model.entity.CustomerAddress
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * Every query is scoped to the owning customer (customerId in the WHERE clause), so one customer
 * can never read or change another customer's address, even with a guessed addressId.
 */
interface CustomerAddressRepository {
    /** The customer's addresses, default first, then newest first. */
    fun findByCustomer(customerId: Long): List<CustomerAddress>

    fun findById(addressId: Long, customerId: Long): CustomerAddress?

    fun findDefault(customerId: Long): CustomerAddress?

    fun countByCustomer(customerId: Long): Long

    /** Inserts an address and returns the generated customeraddressid. */
    fun insert(customerId: Long, details: AddressDetails, isDefault: Boolean): Long

    /** Replaces the delivery details (the service merges a partial PATCH first). Returns rows updated (0 or 1). */
    fun update(addressId: Long, customerId: Long, details: AddressDetails): Int

    /**
     * Removes the default flag from the customer's current default, if any. Must run before
     * markDefault in the same transaction: uq__ec_customeraddress__default allows only one default.
     */
    fun clearDefault(customerId: Long): Int

    /** Makes this address the default. Returns rows updated (0 or 1). */
    fun markDefault(addressId: Long, customerId: Long): Int

    /** Hard delete. Orders keep their own copy of the address, and ordercustomeraddressid becomes NULL. Returns rows deleted. */
    fun delete(addressId: Long, customerId: Long): Int
}

@Repository
class CustomerAddressRepositoryImpl(private val jdbi: Jdbi) : CustomerAddressRepository {

    override fun findByCustomer(customerId: Long): List<CustomerAddress> =
        jdbi.withHandle<List<CustomerAddress>, Exception> { handle ->
            handle.createQuery(CustomerAddressQuery.FIND_BY_CUSTOMER)
                .bind("customerId", customerId)
                .mapTo<CustomerAddress>()
                .list()
        }

    override fun findById(addressId: Long, customerId: Long): CustomerAddress? =
        jdbi.withHandle<CustomerAddress?, Exception> { handle ->
            handle.createQuery(CustomerAddressQuery.FIND_BY_ID)
                .bind("addressId", addressId)
                .bind("customerId", customerId)
                .mapTo<CustomerAddress>()
                .findOne()
                .orElse(null)
        }

    override fun findDefault(customerId: Long): CustomerAddress? =
        jdbi.withHandle<CustomerAddress?, Exception> { handle ->
            handle.createQuery(CustomerAddressQuery.FIND_DEFAULT)
                .bind("customerId", customerId)
                .mapTo<CustomerAddress>()
                .findOne()
                .orElse(null)
        }

    override fun countByCustomer(customerId: Long): Long =
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(CustomerAddressQuery.COUNT_BY_CUSTOMER)
                .bind("customerId", customerId)
                .mapTo<Long>()
                .one()
        }

    override fun insert(customerId: Long, details: AddressDetails, isDefault: Boolean): Long =
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(CustomerAddressQuery.INSERT)
                .bind("customerId", customerId)
                .bindMap(detailParams(details))
                .bind("isDefault", isDefault)
                .mapTo<Long>()
                .one()
        }

    override fun update(addressId: Long, customerId: Long, details: AddressDetails): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CustomerAddressQuery.UPDATE)
                .bindMap(detailParams(details))
                .bind("addressId", addressId)
                .bind("customerId", customerId)
                .execute()
        }

    override fun clearDefault(customerId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CustomerAddressQuery.CLEAR_DEFAULT)
                .bind("customerId", customerId)
                .execute()
        }

    override fun markDefault(addressId: Long, customerId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CustomerAddressQuery.MARK_DEFAULT)
                .bind("addressId", addressId)
                .bind("customerId", customerId)
                .execute()
        }

    override fun delete(addressId: Long, customerId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CustomerAddressQuery.DELETE)
                .bind("addressId", addressId)
                .bind("customerId", customerId)
                .execute()
        }

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
