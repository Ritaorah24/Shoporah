package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.repository.NotificationRepository
import com.rita.ecommerceplatform.repository.api.NotificationChannel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Delivers the outbox in the background (SchedulingConfig switches the schedule on; tests call sendDue directly).
 * One row per transaction: lock it, send it, mark it, commit. A failure is recorded on the row and retried
 * after retry-delay-seconds, up to max-attempts, then the row is FAILED.
 * At least once: if the app dies after sending but before the commit, that message is sent again later.
 */
@Component
class NotificationSender(
    private val notificationRepository: NotificationRepository,
    // A single channel while EMAIL is the only type; pick by NotificationChannel.type once there are more.
    private val channel: NotificationChannel,
    private val transactionTemplate: TransactionTemplate,
    @Value("\${app.notifications.max-attempts}") private val maxAttempts: Int,
    @Value("\${app.notifications.retry-delay-seconds}") private val retryDelaySeconds: Int,
    @Value("\${app.notifications.batch-size}") private val batchSize: Int,
) {

    private val log = LoggerFactory.getLogger(NotificationSender::class.java)

    /** Sends due rows until none are left or batch-size is reached; returns how many rows were tried. */
    @Scheduled(fixedDelayString = "\${app.notifications.poll-delay-ms}")
    fun sendDue(): Int {
        var tried = 0
        while (tried < batchSize && sendNext()) tried++
        return tried
    }

    private fun sendNext(): Boolean = transactionTemplate.execute {
        val notification = notificationRepository.lockNextDue(retryDelaySeconds) ?: return@execute false
        if (notification.channel != channel.type) {
            notificationRepository.markAttemptFailed(notification.notificationId, "No ${notification.channel} channel", maxAttempts)
            return@execute true
        }
        try {
            channel.send(notification.recipient, notification.subject, notification.body)
            notificationRepository.markSent(notification.notificationId)
        } catch (e: Exception) {
            // The reason is saved for support; the body is not logged (it can hold personal details).
            log.warn("Notification {} ({}) failed on attempt {}: {}",
                notification.notificationId, notification.type, notification.attemptCount + 1, e.message)
            notificationRepository.markAttemptFailed(notification.notificationId, e.message ?: e.javaClass.simpleName, maxAttempts)
        }
        true
    } ?: false
}
