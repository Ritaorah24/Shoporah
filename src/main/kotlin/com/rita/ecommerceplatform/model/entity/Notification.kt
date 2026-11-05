package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/**
 * One row of public.ec_notification: a message waiting to be sent (PENDING) or the record of one
 * that was (SENT) or gave up (FAILED). Exactly one of customerId / adminId is set (ck__ec_notification__recipient).
 */
data class Notification(
    var notificationId: Long? = null,
    var notificationCustomerId: Long? = null,
    var notificationAdminId: Int? = null,
    var notificationOrderId: Long? = null,
    var notificationChannel: NotificationChannelType? = null,
    var notificationType: NotificationType? = null,
    var notificationRecipient: String? = null,
    var notificationSubject: String? = null,
    var notificationBody: String? = null,
    var notificationStatus: NotificationStatus? = null,
    var notificationAttemptCount: Int? = null,
    var notificationSentAt: LocalDateTime? = null,
    var notificationFailureReason: String? = null,
    var notificationCreatedAt: LocalDateTime? = null,
    var notificationUpdatedAt: LocalDateTime? = null,
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
