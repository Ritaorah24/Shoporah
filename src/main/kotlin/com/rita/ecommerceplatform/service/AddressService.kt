package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.model.request.CreateAddressRequest
import com.rita.ecommerceplatform.model.request.UpdateAddressRequest
import com.rita.ecommerceplatform.model.response.AddressResponse
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.entity.AddressDetails
import com.rita.ecommerceplatform.model.entity.CustomerAddress
import com.rita.ecommerceplatform.repository.database.CustomerAddressRepository
import com.rita.ecommerceplatform.repository.database.CustomerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * A customer's delivery addresses. Every change first locks the customer row (A1), so two requests
 * for the same customer cannot both make a "first" default address or both pass the address cap.
 */
@Service
class AddressService(
    private val addressRepository: CustomerAddressRepository,
    private val customerRepository: CustomerRepository,
) {

    fun list(customerId: Long): List<AddressResponse> =
        addressRepository.findByCustomer(customerId).map(AddressResponse::from)

    fun get(customerId: Long, addressId: Long): AddressResponse =
        AddressResponse.from(find(customerId, addressId))

    /** The first address is always the default; later ones only when isDefault is true. */
    @Transactional
    fun create(customerId: Long, request: CreateAddressRequest): AddressResponse {
        customerRepository.lockById(customerId)
        val count = addressRepository.countByCustomer(customerId)
        if (count >= MAX_ADDRESSES) throw ConflictException("You can save at most $MAX_ADDRESSES addresses")

        // Fields are already trimmed (TrimStringDeserializer); required ones are non-null after @Valid.
        val details = AddressDetails(
            recipientName = request.recipientName!!,
            recipientPhoneNumber = request.recipientPhoneNumber!!,
            addressLine1 = request.addressLine1!!,
            addressLine2 = request.addressLine2.blankToNull(),
            city = request.city!!,
            state = request.state!!,
            country = request.country!!,
            postalCode = request.postalCode.blankToNull(),
        )
        val makeDefault = count == 0L || request.isDefault == true
        // Only one default is allowed (uq__ec_customeraddress__default), so clear the old one first.
        if (makeDefault) addressRepository.clearDefault(customerId)

        val addressId = addressRepository.insert(customerId, details, makeDefault)
        return get(customerId, addressId)
    }

    /** Partial update: fields not sent keep their value; addressLine2 / postalCode "" clears them. */
    @Transactional
    fun update(customerId: Long, addressId: Long, request: UpdateAddressRequest): AddressResponse {
        customerRepository.lockById(customerId)
        val existing = find(customerId, addressId).details

        val details = AddressDetails(
            recipientName = request.recipientName ?: existing.recipientName,
            recipientPhoneNumber = request.recipientPhoneNumber ?: existing.recipientPhoneNumber,
            addressLine1 = request.addressLine1 ?: existing.addressLine1,
            addressLine2 = if (request.addressLine2 != null) request.addressLine2.blankToNull() else existing.addressLine2,
            city = request.city ?: existing.city,
            state = request.state ?: existing.state,
            country = request.country ?: existing.country,
            postalCode = if (request.postalCode != null) request.postalCode.blankToNull() else existing.postalCode,
        )
        addressRepository.update(addressId, customerId, details)
        return get(customerId, addressId)
    }

    /** Makes this the default address; the previous default is unset in the same transaction. */
    @Transactional
    fun setDefault(customerId: Long, addressId: Long): AddressResponse {
        customerRepository.lockById(customerId)
        val address = find(customerId, addressId)
        if (!address.isDefault) {
            addressRepository.clearDefault(customerId)
            addressRepository.markDefault(addressId, customerId)
        }
        return get(customerId, addressId)
    }

    /** Hard delete. Deleting the default leaves the customer with no default; none is promoted. */
    @Transactional
    fun delete(customerId: Long, addressId: Long) {
        customerRepository.lockById(customerId)
        if (addressRepository.delete(addressId, customerId) == 0) throw addressNotFound()
    }

    // The query is scoped to the customer, so another customer's address is "not found" too.
    private fun find(customerId: Long, addressId: Long): CustomerAddress =
        addressRepository.findById(addressId, customerId) ?: throw addressNotFound()

    private fun addressNotFound() = NotFoundException("Address not found")

    private fun String?.blankToNull(): String? = this?.trim()?.ifEmpty { null }

    companion object {
        // A2: stops one account from filling the table.
        const val MAX_ADDRESSES = 10
    }
}
