package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.utility.SQL_NOW
import com.rita.ecommerceplatform.model.entity.Notification
import com.rita.ecommerceplatform.model.entity.NotificationChannelType
import com.rita.ecommerceplatform.model.entity.NotificationStatus
import com.rita.ecommerceplatform.model.entity.NotificationType
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** The notification outbox and delivery log (ec_notification). */
@Repository
class NotificationRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        Notification(
            notificationId = rs.getLong("notificationid"),
            customerId = rs.getObject("notificationcustomerid", java.lang.Long::class.java)?.toLong(),
            adminId = rs.getObject("notificationadminid", Integer::class.java)?.toInt(),
            orderId = rs.getObject("notificationorderid", java.lang.Long::class.java)?.toLong(),
            channel = NotificationChannelType.valueOf(rs.getString("notificationchannel").uppercase()),
            type = NotificationType.valueOf(rs.getString("notificationtype").uppercase()),
            recipient = rs.getString("notificationrecipient"),
            subject = rs.getString("notificationsubject"),
            body = rs.getString("notificationbody"),
            status = NotificationStatus.valueOf(rs.getString("notificationstatus").uppercase()),
            attemptCount = rs.getInt("notificationattemptcount"),
            sentAt = rs.getObject("notificationsentat", LocalDateTime::class.java),
            failureReason = rs.getString("notificationfailurereason"),
            createdAt = rs.getObject("notificationcreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("notificationupdatedat", LocalDateTime::class.java),
        )
    }

    /**
     * Queues a message (status PENDING, no attempts yet) and returns its notificationid, or null when the
     * event already has its email (V10's unique indexes): the duplicate is skipped, never an error.
     */
    fun insertPending(
        type: NotificationType,
        channel: NotificationChannelType,
        recipient: String,
        subject: String?,
        body: String?,
        customerId: Long?,
        adminId: Int?,
        orderId: Long?,
    ): Long? =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_notification (
                notificationcustomerid, notificationadminid, notificationorderid, notificationchannel,
                notificationtype, notificationrecipient, notificationsubject, notificationbody
            )
            VALUES (:customerId, :adminId, :orderId, :channel, :type, :recipient, :subject, :body)
            ON CONFLICT DO NOTHING
            RETURNING notificationid
            """
        )
            .param("customerId", customerId)
            .param("adminId", adminId)
            .param("orderId", orderId)
            .param("channel", channel.name)
            .param("type", type.name)
            .param("recipient", recipient)
            .param("subject", subject)
            .param("body", body)
            .query(Long::class.javaObjectType)
            .optional()
            .orElse(null)

    /**
     * Records a message that was already sent (or failed) outside the outbox, e.g. a password reset email,
     * whose token must never be stored. Written as SENT or FAILED, so NotificationSender never picks it up.
     */
    fun insertLog(
        type: NotificationType,
        channel: NotificationChannelType,
        recipient: String,
        subject: String?,
        body: String?,
        status: NotificationStatus,
        failureReason: String?,
        customerId: Long?,
        adminId: Int?,
    ): Long {
        require(status != NotificationStatus.PENDING) { "A log row is never PENDING" }
        return jdbcClient.sql(
            """
            INSERT INTO public.ec_notification (
                notificationcustomerid, notificationadminid, notificationchannel, notificationtype,
                notificationrecipient, notificationsubject, notificationbody, notificationstatus,
                notificationattemptcount, notificationsentat, notificationfailurereason
            )
            VALUES (
                :customerId, :adminId, :channel, :type, :recipient, :subject, :body, :status,
                1, CASE WHEN :status = 'SENT' THEN $SQL_NOW END, :reason
            )
            RETURNING notificationid
            """
        )
            .param("customerId", customerId)
            .param("adminId", adminId)
            .param("channel", channel.name)
            .param("type", type.name)
            .param("recipient", recipient)
            .param("subject", subject)
            .param("body", body)
            .param("status", status.name)
            .param("reason", failureReason?.take(MAX_REASON_LENGTH))
            .query(Long::class.javaObjectType)
            .single()
    }

    fun findById(notificationId: Long): Notification? =
        jdbcClient.sql("SELECT * FROM public.ec_notification WHERE notificationid = :id")
            .param("id", notificationId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /**
     * Locks the oldest PENDING row that is due: never tried, or last tried at least retryDelaySeconds ago.
     * SKIP LOCKED: a row another sender (e.g. a second app instance) is working on is skipped, not waited for.
     * Must run inside a transaction, which holds the lock until the row is marked.
     */
    fun lockNextDue(retryDelaySeconds: Int): Notification? =
        jdbcClient.sql(
            """
            SELECT * FROM public.ec_notification
            WHERE notificationstatus = :pending
              AND (notificationattemptcount = 0
                   OR notificationupdatedat <= $SQL_NOW - (:retryDelaySeconds * INTERVAL '1 second'))
            ORDER BY notificationcreatedat, notificationid
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """
        )
            .param("pending", NotificationStatus.PENDING.name)
            .param("retryDelaySeconds", retryDelaySeconds)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun markSent(notificationId: Long): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_notification
            SET notificationstatus = :sent,
                notificationattemptcount = notificationattemptcount + 1,
                notificationsentat = $SQL_NOW,
                notificationfailurereason = NULL,
                notificationupdatedat = $SQL_NOW
            WHERE notificationid = :id
            """
        )
            .param("sent", NotificationStatus.SENT.name)
            .param("id", notificationId)
            .update()

    /** Counts a failed attempt and keeps the reason; the row becomes FAILED once maxAttempts is reached. */
    fun markAttemptFailed(notificationId: Long, reason: String, maxAttempts: Int): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_notification
            SET notificationattemptcount = notificationattemptcount + 1,
                notificationstatus = CASE WHEN notificationattemptcount + 1 >= :maxAttempts THEN :failed ELSE :pending END,
                notificationfailurereason = :reason,
                notificationupdatedat = $SQL_NOW
            WHERE notificationid = :id
            """
        )
            .param("maxAttempts", maxAttempts)
            .param("failed", NotificationStatus.FAILED.name)
            .param("pending", NotificationStatus.PENDING.name)
            .param("reason", reason.take(MAX_REASON_LENGTH))
            .param("id", notificationId)
            .update()

    companion object {
        private const val MAX_REASON_LENGTH = 1000
    }
}
