package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.model.NotificationChannelType
import com.rita.ecommerceplatform.model.NotificationType
import com.rita.ecommerceplatform.repository.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Queues messages in the outbox; NotificationSender delivers them later.
 * MANDATORY: queue only joins the caller's transaction, so the message is saved together with the
 * event it reports (an order, a payment) or not at all, and a slow or broken mail server never
 * affects that event. Calling it outside a transaction is a programming error and throws.
 */
@Service
class NotificationService(private val notificationRepository: NotificationRepository) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun queue(
        type: NotificationType,
        recipient: String,
        subject: String,
        body: String,
        customerId: Long? = null,
        adminId: Int? = null,
        orderId: Long? = null,
        channel: NotificationChannelType = NotificationChannelType.EMAIL,
    ): Long {
        require((customerId == null) != (adminId == null)) { "A notification needs exactly one of customerId or adminId" }
        return notificationRepository.insertPending(type, channel, recipient, subject, body, customerId, adminId, orderId)
    }
}
