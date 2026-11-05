package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.PaymentQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.Payment
import com.rita.ecommerceplatform.model.entity.PaymentStatus
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/** What the provider told us about a payment when we verified it, as saved on the ec_payment row. */
data class VerifiedDetails(
    val transactionId: String?,
    val channel: String?,
    val gatewayResponse: String?,
    val rawResponse: String,
)

/** Payment attempts (ec_payment). An order can have several; at most one ever succeeds (uq__ec_payment__order_success). */
interface PaymentRepository {
    /** Inserts a PENDING attempt (status and timestamps come from column defaults) and returns the generated paymentid. */
    fun insert(orderId: Long, reference: String, provider: String, amount: BigDecimal, currency: String): Long

    fun findById(paymentId: Long): Payment?

    /** The payment with this reference, only if its order belongs to this customer (case-insensitive: CITEXT). */
    fun findByReferenceForCustomer(reference: String, customerId: Long): Payment?

    /** The payment with this reference, whoever's order it is (for the provider's webhook). Case-insensitive: CITEXT. */
    fun findByReference(reference: String): Payment?

    /** Locks the payment row until the transaction ends (after its order's row). Must be called inside a transaction. */
    fun lockById(paymentId: Long): Payment?

    /** The order's SUCCESS payment, if any (at most one: uq__ec_payment__order_success). */
    fun findSuccessfulForOrder(orderId: Long): Payment?

    /** Every payment attempt for the order, oldest first (uses ix__ec_payment__orderid). */
    fun findByOrder(orderId: Long): List<Payment>

    /** The order's newest PENDING attempt, if any (uses ix__ec_payment__orderid). */
    fun findOpenAttempt(orderId: Long): Payment?

    /**
     * Marks FAILED the order's PENDING attempts that never got a payment page and are older than
     * `olderThanSeconds`: the request that started them must have crashed. Returns how many were marked.
     */
    fun failStaleStarts(orderId: Long, olderThanSeconds: Int, reason: String): Int

    /** Saves the payment page the provider gave us. Returns the number of rows updated (0 or 1). */
    fun saveAuthorizationUrl(paymentId: Long, authorizationUrl: String): Int

    /**
     * Records a verified successful payment with the provider's details and its whole answer (JSONB).
     * Returns the number of rows updated (0 if it was no longer PENDING).
     */
    fun markSucceeded(paymentId: Long, details: VerifiedDetails, paidAt: LocalDateTime): Int

    /**
     * Records a verified unsuccessful outcome (FAILED or ABANDONED). `note` replaces the provider's message
     * when we reject a payment ourselves (e.g. a wrong amount). Returns rows updated (0 if no longer PENDING).
     */
    fun markUnsuccessful(paymentId: Long, status: PaymentStatus, details: VerifiedDetails, note: String? = null): Int

    /** Marks a PENDING attempt FAILED. Returns the number of rows updated (0 if it was no longer PENDING). */
    fun markFailed(paymentId: Long, reason: String): Int
}

@Repository
class PaymentRepositoryImpl(private val jdbi: Jdbi) : PaymentRepository {

    override fun insert(orderId: Long, reference: String, provider: String, amount: BigDecimal, currency: String): Long =
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(PaymentQuery.INSERT)
                .bind("orderId", orderId)
                .bind("reference", reference)
                .bind("provider", provider)
                .bind("amount", amount)
                .bind("currency", currency)
                .mapTo<Long>()
                .one()
        }

    override fun findById(paymentId: Long): Payment? =
        jdbi.withHandle<Payment?, Exception> { handle ->
            handle.createQuery(PaymentQuery.FIND_BY_ID)
                .bind("paymentId", paymentId)
                .mapTo<Payment>()
                .findOne()
                .orElse(null)
        }

    override fun findByReferenceForCustomer(reference: String, customerId: Long): Payment? =
        jdbi.withHandle<Payment?, Exception> { handle ->
            handle.createQuery(PaymentQuery.FIND_BY_REFERENCE_FOR_CUSTOMER)
                .bind("reference", reference)
                .bind("customerId", customerId)
                .mapTo<Payment>()
                .findOne()
                .orElse(null)
        }

    override fun findByReference(reference: String): Payment? =
        jdbi.withHandle<Payment?, Exception> { handle ->
            handle.createQuery(PaymentQuery.FIND_BY_REFERENCE)
                .bind("reference", reference)
                .mapTo<Payment>()
                .findOne()
                .orElse(null)
        }

    override fun lockById(paymentId: Long): Payment? =
        jdbi.withHandle<Payment?, Exception> { handle ->
            handle.createQuery(PaymentQuery.LOCK_BY_ID)
                .bind("paymentId", paymentId)
                .mapTo<Payment>()
                .findOne()
                .orElse(null)
        }

    override fun findSuccessfulForOrder(orderId: Long): Payment? =
        jdbi.withHandle<Payment?, Exception> { handle ->
            handle.createQuery(PaymentQuery.FIND_SUCCESSFUL_FOR_ORDER)
                .bind("orderId", orderId)
                .mapTo<Payment>()
                .findOne()
                .orElse(null)
        }

    override fun findByOrder(orderId: Long): List<Payment> =
        jdbi.withHandle<List<Payment>, Exception> { handle ->
            handle.createQuery(PaymentQuery.FIND_BY_ORDER)
                .bind("orderId", orderId)
                .mapTo<Payment>()
                .list()
        }

    override fun findOpenAttempt(orderId: Long): Payment? =
        jdbi.withHandle<Payment?, Exception> { handle ->
            handle.createQuery(PaymentQuery.FIND_OPEN_ATTEMPT)
                .bind("orderId", orderId)
                .mapTo<Payment>()
                .findOne()
                .orElse(null)
        }

    override fun failStaleStarts(orderId: Long, olderThanSeconds: Int, reason: String): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(PaymentQuery.FAIL_STALE_STARTS)
                .bind("orderId", orderId)
                .bind("olderThanSeconds", olderThanSeconds)
                .bind("reason", reason)
                .execute()
        }

    override fun saveAuthorizationUrl(paymentId: Long, authorizationUrl: String): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(PaymentQuery.SAVE_AUTHORIZATION_URL)
                .bind("paymentId", paymentId)
                .bind("authorizationUrl", authorizationUrl)
                .execute()
        }

    override fun markSucceeded(paymentId: Long, details: VerifiedDetails, paidAt: LocalDateTime): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(PaymentQuery.MARK_SUCCEEDED)
                .bind("paymentId", paymentId)
                .bind("transactionId", details.transactionId)
                .bind("channel", details.channel)
                .bind("gatewayResponse", details.gatewayResponse)
                .bind("rawResponse", details.rawResponse)
                .bind("paidAt", paidAt)
                .execute()
        }

    override fun markUnsuccessful(paymentId: Long, status: PaymentStatus, details: VerifiedDetails, note: String?): Int {
        require(status == PaymentStatus.FAILED || status == PaymentStatus.ABANDONED) { "Not an unsuccessful status: $status" }
        return jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(PaymentQuery.MARK_UNSUCCESSFUL)
                .bind("paymentId", paymentId)
                .bind("status", status.name)
                .bind("transactionId", details.transactionId)
                .bind("channel", details.channel)
                .bind("gatewayResponse", note ?: details.gatewayResponse)
                .bind("rawResponse", details.rawResponse)
                .execute()
        }
    }

    override fun markFailed(paymentId: Long, reason: String): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(PaymentQuery.MARK_FAILED)
                .bind("paymentId", paymentId)
                .bind("reason", reason)
                .execute()
        }
}
