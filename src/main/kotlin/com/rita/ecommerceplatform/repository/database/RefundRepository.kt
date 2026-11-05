package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.RefundQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.Refund
import com.rita.ecommerceplatform.model.entity.RefundStatus
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/** What the provider told us about a refund, as saved on the ec_refund row. */
data class RefundProviderDetails(
    val providerRefundId: String,
    val providerStatus: String?,
    val rawResponse: String,
)

/**
 * Refund attempts (ec_refund). A payment has at most one PENDING or PROCESSED refund (uq__ec_refund__payment_active).
 * Every status change is conditional on PENDING, so a refund is settled at most once.
 */
interface RefundRepository {
    /**
     * Inserts a PENDING refund and returns the generated refundid. Throws DuplicateKeyException if the payment
     * already has a PENDING or PROCESSED refund (uq__ec_refund__payment_active): the service turns it into 409.
     */
    fun insert(
        orderId: Long,
        paymentId: Long,
        amount: BigDecimal,
        currency: String,
        reason: String,
        restock: Boolean,
        adminId: Int,
    ): Long

    fun findById(refundId: Long): Refund?

    /** Locks the refund row until the transaction ends (after its order's row). Must be called inside a transaction. */
    fun lockById(refundId: Long): Refund?

    /** Every refund attempt for the order, oldest first (uses ix__ec_refund__orderid). */
    fun findByOrder(orderId: Long): List<Refund>

    /** Our refund with this provider refund id, if any (uq__ec_refund__providerrefundid). */
    fun findByProviderRefundId(providerRefundId: String): Refund?

    /** The payment's PENDING refund the provider has not confirmed yet, if any (at most one: uq__ec_refund__payment_active). */
    fun findUnconfirmedForPayment(paymentId: Long): Refund?

    /** The provider refund ids already recorded on any of our refunds for this payment. */
    fun findProviderRefundIds(paymentId: Long): Set<String>

    /** Saves what the provider says while the refund is still in progress. Returns rows updated (0 if no longer PENDING). */
    fun saveProviderDetails(refundId: Long, details: RefundProviderDetails): Int

    /** Records that the money was sent back. Returns rows updated (0 if no longer PENDING). */
    fun markProcessed(refundId: Long, details: RefundProviderDetails): Int

    /**
     * Records a failed refund with why (the provider's message or our note); `details` is null when the provider
     * never accepted it. Returns rows updated (0 if no longer PENDING).
     */
    fun markFailed(refundId: Long, reason: String, details: RefundProviderDetails? = null): Int

    /**
     * Marks FAILED a PENDING refund the provider never confirmed, once it is older than `olderThanSeconds`
     * (the provider has no record of it, so nothing was sent and it can be tried again). Returns rows updated.
     */
    fun failUnconfirmed(refundId: Long, olderThanSeconds: Int, reason: String): Int
}

@Repository
class RefundRepositoryImpl(private val jdbi: Jdbi) : RefundRepository {

    private val rowMapper = RowMapper { rs, _ ->
        Refund(
            refundId = rs.getLong("refundid"),
            orderId = rs.getLong("refundorderid"),
            paymentId = rs.getLong("refundpaymentid"),
            amount = rs.getBigDecimal("refundamount"),
            currency = rs.getString("refundcurrency"),
            status = RefundStatus.valueOf(rs.getString("refundstatus").uppercase()),
            providerRefundId = rs.getString("refundproviderrefundid"),
            providerStatus = rs.getString("refundproviderstatus"),
            reason = rs.getString("refundreason"),
            restock = rs.getBoolean("refundrestock"),
            requestedByAdminId = rs.getInt("refundrequestedbyadminid"),
            failureReason = rs.getString("refundfailurereason"),
            processedAt = rs.getObject("refundprocessedat", LocalDateTime::class.java),
            createdAt = rs.getObject("refundcreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("refundupdatedat", LocalDateTime::class.java),
        )
    }

    override fun insert(
        orderId: Long,
        paymentId: Long,
        amount: BigDecimal,
        currency: String,
        reason: String,
        restock: Boolean,
        adminId: Int,
    ): Long =
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(RefundQuery.INSERT)
                .bind("orderId", orderId)
                .bind("paymentId", paymentId)
                .bind("amount", amount)
                .bind("currency", currency)
                .bind("reason", reason)
                .bind("restock", restock)
                .bind("adminId", adminId)
                .mapTo<Long>()
                .one()
        }

    override fun findById(refundId: Long): Refund? =
        jdbi.withHandle<Refund?, Exception> { handle ->
            handle.createQuery(RefundQuery.FIND_BY_ID)
                .bind("refundId", refundId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun lockById(refundId: Long): Refund? =
        jdbi.withHandle<Refund?, Exception> { handle ->
            handle.createQuery(RefundQuery.LOCK_BY_ID)
                .bind("refundId", refundId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun findByOrder(orderId: Long): List<Refund> =
        jdbi.withHandle<List<Refund>, Exception> { handle ->
            handle.createQuery(RefundQuery.FIND_BY_ORDER)
                .bind("orderId", orderId)
                .map(rowMapper)
                .list()
        }

    override fun findByProviderRefundId(providerRefundId: String): Refund? =
        jdbi.withHandle<Refund?, Exception> { handle ->
            handle.createQuery(RefundQuery.FIND_BY_PROVIDER_REFUND_ID)
                .bind("providerRefundId", providerRefundId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun findUnconfirmedForPayment(paymentId: Long): Refund? =
        jdbi.withHandle<Refund?, Exception> { handle ->
            handle.createQuery(RefundQuery.FIND_UNCONFIRMED_FOR_PAYMENT)
                .bind("paymentId", paymentId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun findProviderRefundIds(paymentId: Long): Set<String> =
        jdbi.withHandle<Set<String>, Exception> { handle ->
            handle.createQuery(RefundQuery.FIND_PROVIDER_REFUND_IDS)
                .bind("paymentId", paymentId)
                .mapTo<String>()
                .list()
                .filterNotNull()
                .toSet()
        }

    override fun saveProviderDetails(refundId: Long, details: RefundProviderDetails): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(RefundQuery.SAVE_PROVIDER_DETAILS)
                .bind("refundId", refundId)
                .bind("providerRefundId", details.providerRefundId)
                .bind("providerStatus", details.providerStatus)
                .bind("rawResponse", details.rawResponse)
                .execute()
        }

    override fun markProcessed(refundId: Long, details: RefundProviderDetails): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(RefundQuery.MARK_PROCESSED)
                .bind("refundId", refundId)
                .bind("providerRefundId", details.providerRefundId)
                .bind("providerStatus", details.providerStatus)
                .bind("rawResponse", details.rawResponse)
                .execute()
        }

    override fun markFailed(refundId: Long, reason: String, details: RefundProviderDetails?): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(RefundQuery.MARK_FAILED)
                .bind("refundId", refundId)
                .bind("reason", reason)
                .bind("providerRefundId", details?.providerRefundId)
                .bind("providerStatus", details?.providerStatus)
                .bind("rawResponse", details?.rawResponse)
                .execute()
        }

    override fun failUnconfirmed(refundId: Long, olderThanSeconds: Int, reason: String): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(RefundQuery.FAIL_UNCONFIRMED)
                .bind("refundId", refundId)
                .bind("olderThanSeconds", olderThanSeconds)
                .bind("reason", reason)
                .execute()
        }
}
