package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.Payment
import com.rita.ecommerceplatform.model.PaymentStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

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
