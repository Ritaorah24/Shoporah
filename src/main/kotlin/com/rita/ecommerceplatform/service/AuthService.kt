package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.dto.request.ChangePasswordRequest
import com.rita.ecommerceplatform.dto.request.LoginRequest
import com.rita.ecommerceplatform.dto.request.RegisterRequest
import com.rita.ecommerceplatform.dto.response.CustomerProfileResponse
import com.rita.ecommerceplatform.dto.response.TokenResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.InvalidCredentialsException
import com.rita.ecommerceplatform.model.Admin
import com.rita.ecommerceplatform.model.AdminAuthStatus
import com.rita.ecommerceplatform.model.AdminStatus
import com.rita.ecommerceplatform.model.CustomerAuthStatus
import com.rita.ecommerceplatform.model.CustomerStatus
import com.rita.ecommerceplatform.model.PasswordStatus
import com.rita.ecommerceplatform.repository.AdminAuthRepository
import com.rita.ecommerceplatform.repository.AdminRepository
import com.rita.ecommerceplatform.repository.CustomerAuthRepository
import com.rita.ecommerceplatform.repository.CustomerRepository
import com.rita.ecommerceplatform.security.UserType
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val customerRepository: CustomerRepository,
    private val customerAuthRepository: CustomerAuthRepository,
    private val adminRepository: AdminRepository,
    private val adminAuthRepository: AdminAuthRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
) {

    // Checked against when the email is unknown, so that path takes as long as a real
    // password check (BCrypt is slow on purpose). Otherwise response time reveals which emails exist.
    private val dummyHash: String by lazy { passwordEncoder.encode("timing-equaliser-not-a-real-password")!! }

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

    fun login(request: LoginRequest): TokenResponse {
        val email = request.email!!.lowercase()
        val password = request.password!!

        val customer = customerRepository.findByEmail(email)
        val auth = customer?.let { customerAuthRepository.findByCustomerId(it.customerId) }
        if (customer == null || auth == null) {
            passwordEncoder.matches(password, dummyHash)
            throw InvalidCredentialsException()
        }
        if (!passwordEncoder.matches(password, auth.passwordHash)) {
            throw InvalidCredentialsException()
        }
        // Status is checked only after the password, so strangers cannot probe which accounts are suspended.
        if (customer.status != CustomerStatus.ACTIVE || auth.status != CustomerAuthStatus.ACTIVE) {
            throw AccountNotActiveException()
        }

        customerAuthRepository.updateLastLoginAt(customer.customerId)
        return TokenResponse(
            accessToken = jwtService.createToken(customer.customerId, UserType.CUSTOMER, listOf("CUSTOMER")),
            expiresIn = jwtService.expirySeconds,
        )
    }

    /**
     * No new token is returned: customers have no MUST_CHANGE flag to clear, and tokens
     * cannot be revoked (no refresh tokens), so the current one stays valid until it expires.
     */
    fun changeCustomerPassword(customerId: Long, request: ChangePasswordRequest) {
        val currentPassword = request.currentPassword!!
        val newPassword = request.newPassword!!

        val customer = customerRepository.findById(customerId) ?: throw InvalidCredentialsException("Customer account not found")
        val auth = customerAuthRepository.findByCustomerId(customerId)
        if (auth == null || !passwordEncoder.matches(currentPassword, auth.passwordHash)) {
            throw InvalidCredentialsException("Current password is incorrect")
        }
        // Same order as login: status is only revealed after a correct password.
        if (customer.status != CustomerStatus.ACTIVE || auth.status != CustomerAuthStatus.ACTIVE) {
            throw AccountNotActiveException()
        }
        if (passwordEncoder.matches(newPassword, auth.passwordHash)) {
            throw BadRequestException("New password must be different from the current password")
        }

        customerAuthRepository.updatePassword(customerId, passwordEncoder.encode(newPassword)!!)
    }

    fun adminLogin(request: LoginRequest): TokenResponse {
        val email = request.email!!.lowercase()
        val password = request.password!!

        val admin = adminRepository.findByEmail(email)
        val auth = admin?.let { adminAuthRepository.findByAdminId(it.adminId) }
        // ec_admin_auth allows a NULL hash: treat it like an unknown email.
        val hash = auth?.passwordHash
        if (admin == null || hash == null) {
            passwordEncoder.matches(password, dummyHash)
            throw InvalidCredentialsException()
        }
        if (!passwordEncoder.matches(password, hash)) {
            throw InvalidCredentialsException()
        }
        if (admin.status != AdminStatus.ACTIVE || auth.status != AdminAuthStatus.ACTIVE) {
            throw AccountNotActiveException()
        }

        adminAuthRepository.updateLastLoginAt(admin.adminId)
        return adminTokenResponse(admin, mustChangePassword = auth.passwordStatus == PasswordStatus.MUST_CHANGE)
    }

    /**
     * D4: the admin proves who they are with the current password, sets a new one, and gets a
     * fresh token without mustChangePassword so other admin endpoints open up straight away.
     */
    fun changeAdminPassword(adminId: Int, request: ChangePasswordRequest): TokenResponse {
        val currentPassword = request.currentPassword!!
        val newPassword = request.newPassword!!

        val admin = adminRepository.findById(adminId) ?: throw InvalidCredentialsException("Admin account not found")
        val auth = adminAuthRepository.findByAdminId(adminId)
        val hash = auth?.passwordHash
        if (hash == null || !passwordEncoder.matches(currentPassword, hash)) {
            throw InvalidCredentialsException("Current password is incorrect")
        }
        if (admin.status != AdminStatus.ACTIVE || auth.status != AdminAuthStatus.ACTIVE) {
            throw AccountNotActiveException()
        }
        // Otherwise MUST_CHANGE could be cleared by "changing" the temporary password to itself.
        if (passwordEncoder.matches(newPassword, hash)) {
            throw BadRequestException("New password must be different from the current password")
        }

        adminAuthRepository.updatePassword(adminId, passwordEncoder.encode(newPassword)!!)
        return adminTokenResponse(admin, mustChangePassword = false)
    }

    private fun adminTokenResponse(admin: Admin, mustChangePassword: Boolean) = TokenResponse(
        accessToken = jwtService.createToken(
            subjectId = admin.adminId.toLong(),
            userType = UserType.ADMIN,
            roles = listOf(admin.roleName.uppercase()),
            mustChangePassword = mustChangePassword,
        ),
        expiresIn = jwtService.expirySeconds,
        mustChangePassword = mustChangePassword,
    )
}
