package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.dto.request.RegisterRequest
import com.rita.ecommerceplatform.dto.response.CustomerProfileResponse
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.repository.CustomerAuthRepository
import com.rita.ecommerceplatform.repository.CustomerRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val customerRepository: CustomerRepository,
    private val customerAuthRepository: CustomerAuthRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    /**
     * Creates the customer profile and its credentials in one transaction:
     * if either insert fails, neither row is kept.
     */
    @Transactional
    fun register(request: RegisterRequest): CustomerProfileResponse {
        // Fields are non-null here: @Valid in the controller has already rejected blanks.
        // Email is already trimmed during JSON reading (TrimStringDeserializer).
        val email = request.email!!.lowercase()

        // Friendly early check. The unique constraint below is the real guarantee.
        if (customerRepository.existsByEmail(email)) {
            throw ConflictException("Email is already registered")
        }

        val customerId = try {
            customerRepository.insert(
                firstName = request.firstName!!.trim(),
                lastName = request.lastName!!.trim(),
                email = email,
                phoneNumber = request.phoneNumber!!.trim(),
            )
        } catch (e: DuplicateKeyException) {
            // Two registrations with the same email at the same moment: uq__ec_customer__email stops the second.
            throw ConflictException("Email is already registered")
        }

        customerAuthRepository.insert(customerId, passwordEncoder.encode(request.password!!)!!)

        val customer = customerRepository.findById(customerId)
            ?: error("Customer $customerId not found right after insert")
        return CustomerProfileResponse.from(customer)
    }
}
