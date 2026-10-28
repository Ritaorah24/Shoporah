package com.rita.ecommerceplatform.model

import java.time.LocalDateTime

/**
 * One row of public.ec_notification: a message waiting to be sent (PENDING) or the record of one
 * that was (SENT) or gave up (FAILED). Exactly one of customerId / adminId is set (ck__ec_notification__recipient).
 */
data class Notification(
    val notificationId: Long,
    val customerId: Long?,
    val adminId: Int?,
    val orderId: Long?,
    val channel: NotificationChannelType,
    val type: NotificationType,
    val recipient: String,
    val subject: String?,
    val body: String?,
    val status: NotificationStatus,
    val attemptCount: Int,
    val sentAt: LocalDateTime?,
    val failureReason: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Allowed values of ec_notification.notificationchannel (ck__ec_notification__channel). */
enum class NotificationChannelType { EMAIL }

/** Allowed values of ec_notification.notificationtype (ck__ec_notification__type). */
enum class NotificationType {
    REGISTRATION,
    ORDER_CONFIRMATION,
    PAYMENT_CONFIRMATION,
    ORDER_SHIPPED,
    ORDER_DELIVERED,
    ORDER_CANCELLED,
    ORDER_REFUNDED,
    PASSWORD_RESET,
    PASSWORD_CHANGED,
}

/** Allowed values of ec_notification.notificationstatus (ck__ec_notification__status). */
enum class NotificationStatus { PENDING, SENT, FAILED }
