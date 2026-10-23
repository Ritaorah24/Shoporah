package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.Payment
import com.rita.ecommerceplatform.model.PaymentStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
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
@Repository
class PaymentRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        Payment(
            paymentId = rs.getLong("paymentid"),
            orderId = rs.getLong("paymentorderid"),
            reference = rs.getString("paymentreference"),
            provider = rs.getString("paymentprovider"),
            providerTransactionId = rs.getString("paymentprovidertransactionid"),
            amount = rs.getBigDecimal("paymentamount"),
            currency = rs.getString("paymentcurrency"),
            status = PaymentStatus.valueOf(rs.getString("paymentstatus").uppercase()),
            authorizationUrl = rs.getString("paymentauthorizationurl"),
            channel = rs.getString("paymentchannel"),
            gatewayResponse = rs.getString("paymentgatewayresponse"),
            paidAt = rs.getObject("paymentpaidat", LocalDateTime::class.java),
            verifiedAt = rs.getObject("paymentverifiedat", LocalDateTime::class.java),
            createdAt = rs.getObject("paymentcreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("paymentupdatedat", LocalDateTime::class.java),
        )
    }

    /** Inserts a PENDING attempt (status and timestamps come from column defaults) and returns the generated paymentid. */
    fun insert(orderId: Long, reference: String, provider: String, amount: BigDecimal, currency: String): Long =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_payment (paymentorderid, paymentreference, paymentprovider, paymentamount, paymentcurrency)
            VALUES (:orderId, :reference, :provider, :amount, :currency)
            RETURNING paymentid
            """
        )
            .param("orderId", orderId)
            .param("reference", reference)
            .param("provider", provider)
            .param("amount", amount)
            .param("currency", currency)
            .query(Long::class.javaObjectType)
            .single()

    fun findById(paymentId: Long): Payment? =
        jdbcClient.sql("SELECT * FROM public.ec_payment WHERE paymentid = :paymentId")
            .param("paymentId", paymentId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** The payment with this reference, only if its order belongs to this customer (case-insensitive: CITEXT). */
    fun findByReferenceForCustomer(reference: String, customerId: Long): Payment? =
        jdbcClient.sql(
            """
            SELECT p.* FROM public.ec_payment p
            JOIN public.ec_order o ON o.orderid = p.paymentorderid
            WHERE p.paymentreference = :reference AND o.ordercustomerid = :customerId
            """
        )
            .param("reference", reference)
            .param("customerId", customerId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** The payment with this reference, whoever's order it is (for the provider's webhook). Case-insensitive: CITEXT. */
    fun findByReference(reference: String): Payment? =
        jdbcClient.sql("SELECT * FROM public.ec_payment WHERE paymentreference = :reference")
            .param("reference", reference)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** Locks the payment row until the transaction ends (after its order's row). Must be called inside a transaction. */
    fun lockById(paymentId: Long): Payment? =
        jdbcClient.sql("SELECT * FROM public.ec_payment WHERE paymentid = :paymentId FOR UPDATE")
            .param("paymentId", paymentId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** The order's SUCCESS payment, if any (at most one: uq__ec_payment__order_success). */
    fun findSuccessfulForOrder(orderId: Long): Payment? =
        jdbcClient.sql("SELECT * FROM public.ec_payment WHERE paymentorderid = :orderId AND paymentstatus = 'SUCCESS'")
            .param("orderId", orderId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** The order's newest PENDING attempt, if any (uses ix__ec_payment__orderid). */
    fun findOpenAttempt(orderId: Long): Payment? =
        jdbcClient.sql(
            """
            SELECT * FROM public.ec_payment
            WHERE paymentorderid = :orderId AND paymentstatus = 'PENDING'
            ORDER BY paymentcreatedat DESC, paymentid DESC
            LIMIT 1
            """
        )
            .param("orderId", orderId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /**
     * Marks FAILED the order's PENDING attempts that never got a payment page and are older than
     * `olderThanSeconds`: the request that started them must have crashed. Returns how many were marked.
     */
    fun failStaleStarts(orderId: Long, olderThanSeconds: Int, reason: String): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_payment
            SET paymentstatus = 'FAILED', paymentgatewayresponse = :reason, paymentupdatedat = $SQL_NOW
            WHERE paymentorderid = :orderId
              AND paymentstatus = 'PENDING'
              AND paymentauthorizationurl IS NULL
              AND paymentcreatedat < $SQL_NOW - make_interval(secs => :olderThanSeconds)
            """
        )
            .param("orderId", orderId)
            .param("olderThanSeconds", olderThanSeconds)
            .param("reason", reason)
            .update()

    /** Saves the payment page the provider gave us. Returns the number of rows updated (0 or 1). */
    fun saveAuthorizationUrl(paymentId: Long, authorizationUrl: String): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_payment
            SET paymentauthorizationurl = :authorizationUrl, paymentupdatedat = $SQL_NOW
            WHERE paymentid = :paymentId
            """
        )
            .param("paymentId", paymentId)
            .param("authorizationUrl", authorizationUrl)
            .update()

    /**
     * Records a verified successful payment with the provider's details and its whole answer (JSONB).
     * Returns the number of rows updated (0 if it was no longer PENDING).
     */
    fun markSucceeded(paymentId: Long, details: VerifiedDetails, paidAt: LocalDateTime): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_payment
            SET paymentstatus = 'SUCCESS', paymentprovidertransactionid = :transactionId, paymentchannel = :channel,
                paymentgatewayresponse = :gatewayResponse, paymentproviderresponse = CAST(:rawResponse AS jsonb),
                paymentpaidat = :paidAt, paymentverifiedat = $SQL_NOW, paymentupdatedat = $SQL_NOW
            WHERE paymentid = :paymentId AND paymentstatus = 'PENDING'
            """
        )
            .param("paymentId", paymentId)
            .param("transactionId", details.transactionId)
            .param("channel", details.channel)
            .param("gatewayResponse", details.gatewayResponse)
            .param("rawResponse", details.rawResponse)
            .param("paidAt", paidAt)
            .update()

    /**
     * Records a verified unsuccessful outcome (FAILED or ABANDONED). `note` replaces the provider's message
     * when we reject a payment ourselves (e.g. a wrong amount). Returns rows updated (0 if no longer PENDING).
     */
    fun markUnsuccessful(paymentId: Long, status: PaymentStatus, details: VerifiedDetails, note: String? = null): Int {
        require(status == PaymentStatus.FAILED || status == PaymentStatus.ABANDONED) { "Not an unsuccessful status: $status" }
        return jdbcClient.sql(
            """
            UPDATE public.ec_payment
            SET paymentstatus = :status, paymentprovidertransactionid = :transactionId, paymentchannel = :channel,
                paymentgatewayresponse = :gatewayResponse, paymentproviderresponse = CAST(:rawResponse AS jsonb),
                paymentverifiedat = $SQL_NOW, paymentupdatedat = $SQL_NOW
            WHERE paymentid = :paymentId AND paymentstatus = 'PENDING'
            """
        )
            .param("paymentId", paymentId)
            .param("status", status.name)
            .param("transactionId", details.transactionId)
            .param("channel", details.channel)
            .param("gatewayResponse", note ?: details.gatewayResponse)
            .param("rawResponse", details.rawResponse)
            .update()
    }

    /** Marks a PENDING attempt FAILED. Returns the number of rows updated (0 if it was no longer PENDING). */
    fun markFailed(paymentId: Long, reason: String): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_payment
            SET paymentstatus = 'FAILED', paymentgatewayresponse = :reason, paymentupdatedat = $SQL_NOW
            WHERE paymentid = :paymentId AND paymentstatus = 'PENDING'
            """
        )
            .param("paymentId", paymentId)
            .param("reason", reason)
            .update()
}
