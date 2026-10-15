package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.dto.request.UpdateProfileRequest
import com.rita.ecommerceplatform.dto.response.CustomerProfileResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.Customer
import com.rita.ecommerceplatform.model.CustomerStatus
import com.rita.ecommerceplatform.repository.CustomerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CustomerService(private val customerRepository: CustomerRepository) {

    fun getProfile(customerId: Long): CustomerProfileResponse =
        CustomerProfileResponse.from(findActiveCustomer(customerId))

    /** Partial update: fields not sent (null) keep their current value. */
    @Transactional
    fun updateProfile(customerId: Long, request: UpdateProfileRequest): CustomerProfileResponse {
        val existing = findActiveCustomer(customerId)

        customerRepository.updateProfile(
            customerId = customerId,
            firstName = request.firstName?.trim() ?: existing.firstName,
            lastName = request.lastName?.trim() ?: existing.lastName,
            phoneNumber = request.phoneNumber ?: existing.phoneNumber,
        )

        val updated = customerRepository.findById(customerId)
            ?: error("Customer $customerId not found right after update")
        return CustomerProfileResponse.from(updated)
    }

    /**
     * A valid token is not enough: the customer may have been suspended or deleted after
     * the token was issued. These endpoints read the row anyway, so the check is free.
     */
    private fun findActiveCustomer(customerId: Long): Customer {
        val customer = customerRepository.findById(customerId) ?: throw NotFoundException("Customer not found")
        if (customer.status != CustomerStatus.ACTIVE) throw AccountNotActiveException()
        return customer
    }
}
