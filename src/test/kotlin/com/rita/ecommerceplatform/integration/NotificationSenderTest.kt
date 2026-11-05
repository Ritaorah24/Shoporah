package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.model.entity.NotificationChannelType
import com.rita.ecommerceplatform.model.entity.NotificationStatus
import com.rita.ecommerceplatform.model.entity.NotificationType
import com.rita.ecommerceplatform.repository.database.NotificationRepository
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
    private lateinit var notificationRepository: NotificationRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private fun queueFor(email: String, customerId: Long): Long = transactionTemplate.execute {
        notificationService.queue(NotificationType.PASSWORD_CHANGED, email, SUBJECT, "Hello there", customerId = customerId)
    }!!

    // Only this test's message: registering the customer also queues a welcome email to the same address.
    private fun testEmails(email: String) = emailChannel.sentTo(email).filter { it.subject == SUBJECT }

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
            notificationService.queue(NotificationType.PASSWORD_CHANGED, email, SUBJECT, "Hello", customerId = customerId)
        }
    }

    @Test
    fun `queue saves a PENDING row, and a rolled back transaction leaves none`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)

        val notificationId = queueFor(email, customerId)
        val saved = row(notificationId)
        assertEquals(NotificationStatus.PENDING, saved.notificationStatus)
        assertEquals(NotificationChannelType.EMAIL, saved.notificationChannel)
        assertEquals(NotificationType.PASSWORD_CHANGED, saved.notificationType)
        assertEquals(email, saved.notificationRecipient)
        assertEquals(SUBJECT, saved.notificationSubject)
        assertEquals(customerId, saved.notificationCustomerId)
        assertEquals(0, saved.notificationAttemptCount)

        val rolledBackId = transactionTemplate.execute { status ->
            notificationService.queue(NotificationType.PASSWORD_CHANGED, email, "Again", "Hello", customerId = customerId)
                .also { status.setRollbackOnly() }
        }!!
        assertNull(notificationRepository.findById(rolledBackId))
    }

    @Test
    fun `queue needs exactly one of customerId or adminId`() {
        assertFailsWith<IllegalArgumentException> {
            transactionTemplate.execute {
                notificationService.queue(NotificationType.PASSWORD_CHANGED, uniqueEmail(), SUBJECT, "Hello")
            }
        }
    }

    @Test
    fun `sendDue sends a queued message once and marks it SENT`() {
        val email = uniqueEmail()
        val notificationId = queueFor(email, registerCustomer(email))

        drainOutbox()

        val sent = row(notificationId)
        assertEquals(NotificationStatus.SENT, sent.notificationStatus)
        assertEquals(1, sent.notificationAttemptCount)
        assertNotNull(sent.notificationSentAt)
        assertNull(sent.notificationFailureReason)
        assertEquals(listOf(SUBJECT), testEmails(email).map { it.subject })
        assertEquals("Hello there", testEmails(email).single().body)

        drainOutbox()
        assertEquals(1, testEmails(email).size)
    }

    @Test
    fun `a failed send is retried only after the delay and becomes FAILED after max attempts`() {
        val email = uniqueEmail()
        val notificationId = queueFor(email, registerCustomer(email))

        try {
            emailChannel.failWith = "SMTP server unreachable"

            drainOutbox()
            row(notificationId).let {
                assertEquals(NotificationStatus.PENDING, it.notificationStatus)
                assertEquals(1, it.notificationAttemptCount)
                assertEquals("SMTP server unreachable", it.notificationFailureReason)
            }

            // Not due yet: the last attempt was just now.
            drainOutbox()
            assertEquals(1, row(notificationId).notificationAttemptCount)

            repeat(4) {
                backdate(notificationId)
                drainOutbox()
            }
            row(notificationId).let {
                assertEquals(NotificationStatus.FAILED, it.notificationStatus)
                assertEquals(5, it.notificationAttemptCount)
                assertNull(it.notificationSentAt)
            }

            // A FAILED row is never picked up again.
            backdate(notificationId)
            drainOutbox()
            assertEquals(5, row(notificationId).notificationAttemptCount)
        } finally {
            emailChannel.failWith = null
        }
        assertEquals(0, testEmails(email).size)
    }

    @Test
    fun `a message that failed once is sent on a later try and the reason is cleared`() {
        val email = uniqueEmail()
        val notificationId = queueFor(email, registerCustomer(email))

        try {
            emailChannel.failWith = "Temporary failure"
            drainOutbox()
        } finally {
            emailChannel.failWith = null
        }
        backdate(notificationId)
        drainOutbox()

        row(notificationId).let {
            assertEquals(NotificationStatus.SENT, it.notificationStatus)
            assertEquals(2, it.notificationAttemptCount)
            assertNull(it.notificationFailureReason)
        }
        assertEquals(1, testEmails(email).size)
    }

    companion object {
        private const val SUBJECT = "Test message"
    }
}
