package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.AccessNotAllowedException
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.IncorrectPasswordException
import com.rita.ecommerceplatform.exception.RecordAlreadyExistException
import com.rita.ecommerceplatform.model.request.ChangePasswordRequest
import com.rita.ecommerceplatform.model.request.LoginRequest
import com.rita.ecommerceplatform.model.request.RegisterRequest
import com.rita.ecommerceplatform.model.response.CustomerProfileResponse
import com.rita.ecommerceplatform.model.response.TokenResponse
import com.rita.ecommerceplatform.model.entity.Admin
import com.rita.ecommerceplatform.model.entity.AdminAuthStatus
import com.rita.ecommerceplatform.model.entity.AdminStatus
import com.rita.ecommerceplatform.model.entity.CustomerAuthStatus
import com.rita.ecommerceplatform.model.entity.CustomerStatus
import com.rita.ecommerceplatform.model.entity.NotificationType
import com.rita.ecommerceplatform.model.entity.PasswordStatus
import com.rita.ecommerceplatform.repository.database.AdminAuthRepository
import com.rita.ecommerceplatform.repository.database.AdminRepository
import com.rita.ecommerceplatform.repository.database.CustomerAuthRepository
import com.rita.ecommerceplatform.repository.database.CustomerRepository
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
    private val notificationService: NotificationService,
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
            throw RecordAlreadyExistException("Email is already registered")
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
            throw RecordAlreadyExistException("Email is already registered")
        }

        customerAuthRepository.insert(customerId, passwordEncoder.encode(request.password!!)!!)

        val customer = customerRepository.findById(customerId)
            ?: error("Customer $customerId not found right after insert")
        notificationService.queueToCustomer(NotificationType.REGISTRATION, customer, NotificationMessages.registration(customer.customerFirstName!!))
        return CustomerProfileResponse.from(customer)
    }

    fun login(request: LoginRequest): TokenResponse {
        val email = request.email!!.lowercase()
        val password = request.password!!

        val customer = customerRepository.findByEmail(email)
        val auth = customer?.let { customerAuthRepository.findByCustomerId(it.customerId!!) }
        if (customer == null || auth == null) {
            passwordEncoder.matches(password, dummyHash)
            throw IncorrectPasswordException("Invalid email or password")
        }
        if (!passwordEncoder.matches(password, auth.customerAuthPasswordHash)) {
            throw IncorrectPasswordException("Invalid email or password")
        }
        // Status is checked only after the password, so strangers cannot probe which accounts are suspended.
        if (customer.customerStatus != CustomerStatus.ACTIVE || auth.customerAuthStatus != CustomerAuthStatus.ACTIVE) {
            throw AccessNotAllowedException("Account is not active")
        }

        customerAuthRepository.updateLastLoginAt(customer.customerId!!)
        return TokenResponse(
            accessToken = jwtService.createToken(customer.customerId!!, UserType.CUSTOMER, listOf("CUSTOMER")),
            expiresIn = jwtService.expirySeconds,
        )
    }

    /**
     * No new token is returned: customers have no MUST_CHANGE flag to clear, and tokens
     * cannot be revoked (no refresh tokens), so the current one stays valid until it expires.
     * One transaction, so the new password and its "password changed" email are saved together.
     */
    @Transactional
    fun changeCustomerPassword(customerId: Long, request: ChangePasswordRequest) {
        val currentPassword = request.currentPassword!!
        val newPassword = request.newPassword!!

        val customer = customerRepository.findById(customerId) ?: throw IncorrectPasswordException("Customer account not found")
        val auth = customerAuthRepository.findByCustomerId(customerId)
        if (auth == null || !passwordEncoder.matches(currentPassword, auth.customerAuthPasswordHash)) {
            throw IncorrectPasswordException("Current password is incorrect")
        }
        // Same order as login: status is only revealed after a correct password.
        if (customer.customerStatus != CustomerStatus.ACTIVE || auth.customerAuthStatus != CustomerAuthStatus.ACTIVE) {
            throw AccessNotAllowedException("Account is not active")
        }
        if (passwordEncoder.matches(newPassword, auth.customerAuthPasswordHash)) {
            throw BadRequestException("New password must be different from the current password")
        }

        customerAuthRepository.updatePassword(customerId, passwordEncoder.encode(newPassword)!!)
        notificationService.queueToCustomer(NotificationType.PASSWORD_CHANGED, customer, NotificationMessages.passwordChanged(customer.customerFirstName!!))
    }

    fun adminLogin(request: LoginRequest): TokenResponse {
        val email = request.email!!.lowercase()
        val password = request.password!!

        val admin = adminRepository.findByEmail(email)
        val auth = admin?.let { adminAuthRepository.findByAdminId(it.adminId!!) }
        // ec_admin_auth allows a NULL hash: treat it like an unknown email.
        val hash = auth?.adminAuthPasswordHash
        if (admin == null || hash == null) {
            passwordEncoder.matches(password, dummyHash)
            throw IncorrectPasswordException("Invalid email or password")
        }
        if (!passwordEncoder.matches(password, hash)) {
            throw IncorrectPasswordException("Invalid email or password")
        }
        if (admin.adminStatus != AdminStatus.ACTIVE || auth.adminAuthStatus != AdminAuthStatus.ACTIVE) {
            throw AccessNotAllowedException("Account is not active")
        }

        adminAuthRepository.updateLastLoginAt(admin.adminId!!)
        return adminTokenResponse(admin, mustChangePassword = auth.adminAuthPasswordStatus == PasswordStatus.MUST_CHANGE)
    }

    /**
     * D4: the admin proves who they are with the current password, sets a new one, and gets a
     * fresh token without mustChangePassword so other admin endpoints open up straight away.
     */
    fun changeAdminPassword(adminId: Int, request: ChangePasswordRequest): TokenResponse {
        val currentPassword = request.currentPassword!!
        val newPassword = request.newPassword!!

        val admin = adminRepository.findById(adminId) ?: throw IncorrectPasswordException("Admin account not found")
        val auth = adminAuthRepository.findByAdminId(adminId)
        val hash = auth?.adminAuthPasswordHash
        if (hash == null || !passwordEncoder.matches(currentPassword, hash)) {
            throw IncorrectPasswordException("Current password is incorrect")
        }
        if (admin.adminStatus != AdminStatus.ACTIVE || auth.adminAuthStatus != AdminAuthStatus.ACTIVE) {
            throw AccessNotAllowedException("Account is not active")
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
            subjectId = admin.adminId!!.toLong(),
            userType = UserType.ADMIN,
            roles = listOf(admin.roleName!!.uppercase()),
            mustChangePassword = mustChangePassword,
        ),
        expiresIn = jwtService.expirySeconds,
        mustChangePassword = mustChangePassword,
    )
}
