package com.rita.ecommerceplatform.service

import io.github.oshai.kotlinlogging.KotlinLogging
import com.rita.ecommerceplatform.config.NotificationProps
import com.rita.ecommerceplatform.repository.database.NotificationRepository
import com.rita.ecommerceplatform.repository.api.NotificationChannel
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

private val logger = KotlinLogging.logger {}

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
    private val notificationProps: NotificationProps,
) {

    /** Sends due rows until none are left or batch-size is reached; returns how many rows were tried. */
    @Scheduled(fixedDelayString = "\${notifications.poll-delay-ms}")
    fun sendDue(): Int {
        var tried = 0
        while (tried < notificationProps.batchSize && sendNext()) tried++
        return tried
    }

    private fun sendNext(): Boolean = transactionTemplate.execute {
        val notification = notificationRepository.lockNextDue(notificationProps.retryDelaySeconds) ?: return@execute false
        if (notification.notificationChannel != channel.type) {
            notificationRepository.markAttemptFailed(notification.notificationId!!, "No ${notification.notificationChannel} channel", notificationProps.maxAttempts)
            return@execute true
        }
        try {
            channel.send(notification.notificationRecipient!!, notification.notificationSubject, notification.notificationBody)
            notificationRepository.markSent(notification.notificationId!!)
        } catch (e: Exception) {
            // The reason is saved for support; the body is not logged (it can hold personal details).
            logger.warn { "Notification ${notification.notificationId} (${notification.notificationType}) failed on attempt ${notification.notificationAttemptCount!! + 1}: ${e.message}" }
            notificationRepository.markAttemptFailed(notification.notificationId!!, e.message ?: e.javaClass.simpleName, notificationProps.maxAttempts)
        }
        true
    } ?: false
}
