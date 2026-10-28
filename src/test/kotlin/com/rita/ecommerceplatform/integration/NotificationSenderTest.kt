package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.model.NotificationChannelType
import com.rita.ecommerceplatform.model.NotificationStatus
import com.rita.ecommerceplatform.model.NotificationType
import com.rita.ecommerceplatform.repository.NotificationRepository
import com.rita.ecommerceplatform.service.NotificationSender
import com.rita.ecommerceplatform.service.NotificationService
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NotificationSenderTest : IntegrationTestBase() {

    @Autowired
    private lateinit var notificationService: NotificationService

    @Autowired
    private lateinit var notificationSender: NotificationSender

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private fun queueFor(email: String, customerId: Long): Long = transactionTemplate.execute {
        notificationService.queue(NotificationType.REGISTRATION, email, "Welcome", "Hello there", customerId = customerId)
    }!!

    private fun row(notificationId: Long) = notificationRepository.findById(notificationId)!!

    // Makes the last attempt look older than the retry delay (60 s), so the row is due again.
    private fun backdate(notificationId: Long) {
        jdbcClient.sql(
            "UPDATE public.ec_notification SET notificationupdatedat = notificationupdatedat - INTERVAL '2 minutes' WHERE notificationid = :id"
        )
            .param("id", notificationId)
            .update()
    }

    @Test
    fun `queue outside a transaction is refused`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)

        assertFailsWith<IllegalTransactionStateException> {
            notificationService.queue(NotificationType.REGISTRATION, email, "Welcome", "Hello", customerId = customerId)
        }
    }

    @Test
    fun `queue saves a PENDING row, and a rolled back transaction leaves none`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)

        val notificationId = queueFor(email, customerId)
        val saved = row(notificationId)
        assertEquals(NotificationStatus.PENDING, saved.status)
        assertEquals(NotificationChannelType.EMAIL, saved.channel)
        assertEquals(NotificationType.REGISTRATION, saved.type)
        assertEquals(email, saved.recipient)
        assertEquals("Welcome", saved.subject)
        assertEquals(customerId, saved.customerId)
        assertEquals(0, saved.attemptCount)

        val rolledBackId = transactionTemplate.execute { status ->
            notificationService.queue(NotificationType.REGISTRATION, email, "Again", "Hello", customerId = customerId)
                .also { status.setRollbackOnly() }
        }!!
        assertNull(notificationRepository.findById(rolledBackId))
    }

    @Test
    fun `queue needs exactly one of customerId or adminId`() {
        assertFailsWith<IllegalArgumentException> {
            transactionTemplate.execute {
                notificationService.queue(NotificationType.REGISTRATION, uniqueEmail(), "Welcome", "Hello")
            }
        }
    }

    @Test
    fun `sendDue sends a queued message once and marks it SENT`() {
        val email = uniqueEmail()
        val notificationId = queueFor(email, registerCustomer(email))

        notificationSender.sendDue()

        val sent = row(notificationId)
        assertEquals(NotificationStatus.SENT, sent.status)
        assertEquals(1, sent.attemptCount)
        assertNotNull(sent.sentAt)
        assertNull(sent.failureReason)
        assertEquals(listOf("Welcome"), emailChannel.sentTo(email).map { it.subject })
        assertEquals("Hello there", emailChannel.sentTo(email).single().body)

        notificationSender.sendDue()
        assertEquals(1, emailChannel.sentTo(email).size)
    }

    @Test
    fun `a failed send is retried only after the delay and becomes FAILED after max attempts`() {
        val email = uniqueEmail()
        val notificationId = queueFor(email, registerCustomer(email))

        try {
            emailChannel.failWith = "SMTP server unreachable"

            notificationSender.sendDue()
            row(notificationId).let {
                assertEquals(NotificationStatus.PENDING, it.status)
                assertEquals(1, it.attemptCount)
                assertEquals("SMTP server unreachable", it.failureReason)
            }

            // Not due yet: the last attempt was just now.
            notificationSender.sendDue()
            assertEquals(1, row(notificationId).attemptCount)

            repeat(4) {
                backdate(notificationId)
                notificationSender.sendDue()
            }
            row(notificationId).let {
                assertEquals(NotificationStatus.FAILED, it.status)
                assertEquals(5, it.attemptCount)
                assertNull(it.sentAt)
            }

            // A FAILED row is never picked up again.
            backdate(notificationId)
            notificationSender.sendDue()
            assertEquals(5, row(notificationId).attemptCount)
        } finally {
            emailChannel.failWith = null
        }
        assertEquals(0, emailChannel.sentTo(email).size)
    }

    @Test
    fun `a message that failed once is sent on a later try and the reason is cleared`() {
        val email = uniqueEmail()
        val notificationId = queueFor(email, registerCustomer(email))

        try {
            emailChannel.failWith = "Temporary failure"
            notificationSender.sendDue()
        } finally {
            emailChannel.failWith = null
        }
        backdate(notificationId)
        notificationSender.sendDue()

        row(notificationId).let {
            assertEquals(NotificationStatus.SENT, it.status)
            assertEquals(2, it.attemptCount)
            assertNull(it.failureReason)
        }
        assertEquals(1, emailChannel.sentTo(email).size)
    }
}
