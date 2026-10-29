package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.Refund
import com.rita.ecommerceplatform.model.RefundStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
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
@Repository
class RefundRepository(private val jdbcClient: JdbcClient) {

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
    ): Long =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_refund (
                refundorderid, refundpaymentid, refundamount, refundcurrency, refundreason, refundrestock, refundrequestedbyadminid
            )
            VALUES (:orderId, :paymentId, :amount, :currency, :reason, :restock, :adminId)
            RETURNING refundid
            """
        )
            .param("orderId", orderId)
            .param("paymentId", paymentId)
            .param("amount", amount)
            .param("currency", currency)
            .param("reason", reason)
            .param("restock", restock)
            .param("adminId", adminId)
            .query(Long::class.javaObjectType)
            .single()

    fun findById(refundId: Long): Refund? =
        jdbcClient.sql("SELECT * FROM public.ec_refund WHERE refundid = :refundId")
            .param("refundId", refundId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** Locks the refund row until the transaction ends (after its order's row). Must be called inside a transaction. */
    fun lockById(refundId: Long): Refund? =
        jdbcClient.sql("SELECT * FROM public.ec_refund WHERE refundid = :refundId FOR UPDATE")
            .param("refundId", refundId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** Every refund attempt for the order, oldest first (uses ix__ec_refund__orderid). */
    fun findByOrder(orderId: Long): List<Refund> =
        jdbcClient.sql("SELECT * FROM public.ec_refund WHERE refundorderid = :orderId ORDER BY refundcreatedat, refundid")
            .param("orderId", orderId)
            .query(rowMapper)
            .list()

    /** The provider refund ids already recorded on any of our refunds for this payment. */
    fun findProviderRefundIds(paymentId: Long): Set<String> =
        jdbcClient.sql(
            "SELECT refundproviderrefundid FROM public.ec_refund WHERE refundpaymentid = :paymentId AND refundproviderrefundid IS NOT NULL"
        )
            .param("paymentId", paymentId)
            .query(String::class.java)
            .list()
            .filterNotNull()
            .toSet()

    /** Saves what the provider says while the refund is still in progress. Returns rows updated (0 if no longer PENDING). */
    fun saveProviderDetails(refundId: Long, details: RefundProviderDetails): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_refund
            SET refundproviderrefundid = :providerRefundId, refundproviderstatus = :providerStatus,
                refundproviderresponse = CAST(:rawResponse AS jsonb), refundupdatedat = $SQL_NOW
            WHERE refundid = :refundId AND refundstatus = 'PENDING'
            """
        )
            .param("refundId", refundId)
            .param("providerRefundId", details.providerRefundId)
            .param("providerStatus", details.providerStatus)
            .param("rawResponse", details.rawResponse)
            .update()

    /** Records that the money was sent back. Returns rows updated (0 if no longer PENDING). */
    fun markProcessed(refundId: Long, details: RefundProviderDetails): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_refund
            SET refundstatus = 'PROCESSED', refundproviderrefundid = :providerRefundId, refundproviderstatus = :providerStatus,
                refundproviderresponse = CAST(:rawResponse AS jsonb), refundprocessedat = $SQL_NOW, refundupdatedat = $SQL_NOW
            WHERE refundid = :refundId AND refundstatus = 'PENDING'
            """
        )
            .param("refundId", refundId)
            .param("providerRefundId", details.providerRefundId)
            .param("providerStatus", details.providerStatus)
            .param("rawResponse", details.rawResponse)
            .update()

    /**
     * Records a failed refund with why (the provider's message or our note); `details` is null when the provider
     * never accepted it. Returns rows updated (0 if no longer PENDING).
     */
    fun markFailed(refundId: Long, reason: String, details: RefundProviderDetails? = null): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_refund
            SET refundstatus = 'FAILED', refundfailurereason = :reason,
                refundproviderrefundid = COALESCE(:providerRefundId, refundproviderrefundid),
                refundproviderstatus = COALESCE(:providerStatus, refundproviderstatus),
                refundproviderresponse = COALESCE(CAST(:rawResponse AS jsonb), refundproviderresponse),
                refundupdatedat = $SQL_NOW
            WHERE refundid = :refundId AND refundstatus = 'PENDING'
            """
        )
            .param("refundId", refundId)
            .param("reason", reason)
            .param("providerRefundId", details?.providerRefundId)
            .param("providerStatus", details?.providerStatus)
            .param("rawResponse", details?.rawResponse)
            .update()

    /**
     * Marks FAILED a PENDING refund the provider never confirmed, once it is older than `olderThanSeconds`
     * (the provider has no record of it, so nothing was sent and it can be tried again). Returns rows updated.
     */
    fun failUnconfirmed(refundId: Long, olderThanSeconds: Int, reason: String): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_refund
            SET refundstatus = 'FAILED', refundfailurereason = :reason, refundupdatedat = $SQL_NOW
            WHERE refundid = :refundId
              AND refundstatus = 'PENDING'
              AND refundproviderrefundid IS NULL
              AND refundcreatedat < $SQL_NOW - make_interval(secs => :olderThanSeconds)
            """
        )
            .param("refundId", refundId)
            .param("olderThanSeconds", olderThanSeconds)
            .param("reason", reason)
            .update()
}
