package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.config.PasswordResetProps
import com.rita.ecommerceplatform.exception.AccessNotAllowedException
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.config.EmailExecutorConfig
import com.rita.ecommerceplatform.model.request.ForgotPasswordRequest
import com.rita.ecommerceplatform.model.request.ResetPasswordRequest
import com.rita.ecommerceplatform.model.entity.Customer
import com.rita.ecommerceplatform.model.entity.CustomerAuthStatus
import com.rita.ecommerceplatform.model.entity.CustomerStatus
import com.rita.ecommerceplatform.model.entity.NotificationStatus
import com.rita.ecommerceplatform.model.entity.NotificationType
import com.rita.ecommerceplatform.repository.database.CustomerAuthRepository
import com.rita.ecommerceplatform.repository.database.CustomerRepository
import com.rita.ecommerceplatform.repository.database.NotificationRepository
import com.rita.ecommerceplatform.repository.api.NotificationChannel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * Forgot / reset password for customers (checkpoint 2.1).
 * The raw token exists only in the email: the database keeps its SHA-256 hash (the token is long and
 * random, so a fast hash is safe, and it lets us find the account by it). The email is sent directly,
 * not through the outbox, because the outbox would have to store the token.
 */
@Service
class PasswordResetService(
    private val customerRepository: CustomerRepository,
    private val customerAuthRepository: CustomerAuthRepository,
    private val notificationRepository: NotificationRepository,
    private val notificationService: NotificationService,
    private val channel: NotificationChannel,
    private val passwordEncoder: PasswordEncoder,
    @Qualifier(EmailExecutorConfig.EMAIL_EXECUTOR) private val emailExecutor: TaskExecutor,
    private val passwordResetProps: PasswordResetProps,
) {

    private val log = LoggerFactory.getLogger(PasswordResetService::class.java)
    private val random = SecureRandom()

    /**
     * Always ends the same way for the caller (the controller sends one fixed reply), so it cannot be used
     * to find out which emails have accounts. Only an active customer outside the cooldown gets an email,
     * and it is sent on a background thread so known and unknown emails answer equally fast.
     */
    fun requestReset(request: ForgotPasswordRequest) {
        val customer = customerRepository.findByEmail(request.email!!.lowercase()) ?: return
        val auth = customerAuthRepository.findByCustomerId(customer.customerId!!) ?: return
        if (customer.customerStatus != CustomerStatus.ACTIVE || auth.customerAuthStatus != CustomerAuthStatus.ACTIVE) return

        val token = newToken()
        if (customerAuthRepository.saveResetToken(customer.customerId!!, hash(token), passwordResetProps.tokenMinutes, passwordResetProps.cooldownSeconds) == 0) {
            return // a link was sent less than cooldownSeconds ago
        }
        emailExecutor.execute { sendResetEmail(customer, token) }
    }

    /**
     * Works once per token: the conditional UPDATE clears it, so a second use (even at the same moment) fails.
     * One transaction, so the new password and its "password changed" email are saved together.
     */
    @Transactional
    fun resetPassword(request: ResetPasswordRequest) {
        val tokenHash = hash(request.token!!)
        val auth = customerAuthRepository.findByValidResetTokenHash(tokenHash) ?: throw invalidLink()
        val customer = customerRepository.findById(auth.customerAuthCustomerId!!) ?: throw invalidLink()
        // Only someone holding a valid link gets this far, so revealing the status here is fine.
        if (customer.customerStatus != CustomerStatus.ACTIVE || auth.customerAuthStatus != CustomerAuthStatus.ACTIVE) {
            throw AccessNotAllowedException("Account is not active")
        }
        if (customerAuthRepository.resetPassword(tokenHash, passwordEncoder.encode(request.newPassword!!)!!) != 1) {
            throw invalidLink()
        }
        notificationService.queueToCustomer(NotificationType.PASSWORD_CHANGED, customer, NotificationMessages.passwordChanged(customer.customerFirstName!!))
    }

    private fun sendResetEmail(customer: Customer, token: String) {
        val subject = "Reset your Shoporah password"
        val body = """
            Hi ${customer.customerFirstName},

            We received a request to reset your password. Use this link within ${passwordResetProps.tokenMinutes} minutes:
            ${passwordResetProps.url}?token=$token

            If you didn't ask for this, ignore this email; your password stays the same.
        """.trimIndent()

        val (status, reason) = try {
            channel.send(customer.customerEmail!!, subject, body)
            NotificationStatus.SENT to null
        } catch (e: Exception) {
            log.warn("Password reset email for customer {} failed: {}", customer.customerId, e.message)
            NotificationStatus.FAILED to (e.message ?: e.javaClass.simpleName)
        }
        try {
            notificationRepository.insertLog(
                type = NotificationType.PASSWORD_RESET,
                channel = channel.type,
                recipient = customer.customerEmail!!,
                subject = subject,
                body = LOG_BODY,
                status = status,
                failureReason = reason,
                customerId = customer.customerId,
                adminId = null,
            )
        } catch (e: Exception) {
            log.error("Could not record the password reset email for customer {}", customer.customerId, e)
        }
    }

    // 32 random bytes = 256 bits, URL-safe so it can sit in the link as it is (43 characters).
    private fun newToken(): String =
        ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun hash(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    private fun invalidLink() = BadRequestException("Reset link is invalid or has expired")

    companion object {
        private const val TOKEN_BYTES = 32

        /** What the log row says instead of the real email body, which holds the token. */
        const val LOG_BODY = "Password reset link sent (the link is not stored)"

        const val FORGOT_REPLY = "If an account exists for this email, a reset link has been sent."
    }
}
