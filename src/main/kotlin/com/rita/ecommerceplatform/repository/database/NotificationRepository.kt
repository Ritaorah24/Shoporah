package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.NotificationQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.Notification
import com.rita.ecommerceplatform.model.entity.NotificationChannelType
import com.rita.ecommerceplatform.model.entity.NotificationStatus
import com.rita.ecommerceplatform.model.entity.NotificationType
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** The notification outbox and delivery log (ec_notification). */
interface NotificationRepository {
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
    ): Long?

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
    ): Long

    fun findById(notificationId: Long): Notification?

    /**
     * Locks the oldest PENDING row that is due: never tried, or last tried at least retryDelaySeconds ago.
     * SKIP LOCKED: a row another sender (e.g. a second app instance) is working on is skipped, not waited for.
     * Must run inside a transaction, which holds the lock until the row is marked.
     */
    fun lockNextDue(retryDelaySeconds: Int): Notification?

    fun markSent(notificationId: Long): Int

    /** Counts a failed attempt and keeps the reason; the row becomes FAILED once maxAttempts is reached. */
    fun markAttemptFailed(notificationId: Long, reason: String, maxAttempts: Int): Int
}

@Repository
class NotificationRepositoryImpl(private val jdbi: Jdbi) : NotificationRepository {

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

    override fun insertPending(
        type: NotificationType,
        channel: NotificationChannelType,
        recipient: String,
        subject: String?,
        body: String?,
        customerId: Long?,
        adminId: Int?,
        orderId: Long?,
    ): Long? =
        jdbi.withHandle<Long?, Exception> { handle ->
            handle.createQuery(NotificationQuery.INSERT_PENDING)
                .bind("customerId", customerId)
                .bind("adminId", adminId)
                .bind("orderId", orderId)
                .bind("channel", channel.name)
                .bind("type", type.name)
                .bind("recipient", recipient)
                .bind("subject", subject)
                .bind("body", body)
                .mapTo<Long>()
                .findOne()
                .orElse(null)
        }

    override fun insertLog(
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
        return jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(NotificationQuery.INSERT_LOG)
                .bind("customerId", customerId)
                .bind("adminId", adminId)
                .bind("channel", channel.name)
                .bind("type", type.name)
                .bind("recipient", recipient)
                .bind("subject", subject)
                .bind("body", body)
                .bind("status", status.name)
                .bind("reason", failureReason?.take(MAX_REASON_LENGTH))
                .mapTo<Long>()
                .one()
        }
    }

    override fun findById(notificationId: Long): Notification? =
        jdbi.withHandle<Notification?, Exception> { handle ->
            handle.createQuery(NotificationQuery.FIND_BY_ID)
                .bind("id", notificationId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun lockNextDue(retryDelaySeconds: Int): Notification? =
        jdbi.withHandle<Notification?, Exception> { handle ->
            handle.createQuery(NotificationQuery.LOCK_NEXT_DUE)
                .bind("pending", NotificationStatus.PENDING.name)
                .bind("retryDelaySeconds", retryDelaySeconds)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun markSent(notificationId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(NotificationQuery.MARK_SENT)
                .bind("sent", NotificationStatus.SENT.name)
                .bind("id", notificationId)
                .execute()
        }

    override fun markAttemptFailed(notificationId: Long, reason: String, maxAttempts: Int): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(NotificationQuery.MARK_ATTEMPT_FAILED)
                .bind("maxAttempts", maxAttempts)
                .bind("failed", NotificationStatus.FAILED.name)
                .bind("pending", NotificationStatus.PENDING.name)
                .bind("reason", reason.take(MAX_REASON_LENGTH))
                .bind("id", notificationId)
                .execute()
        }

    companion object {
        private const val MAX_REASON_LENGTH = 1000
    }
}
