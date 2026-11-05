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
        assertEquals(NotificationStatus.PENDING, saved.status)
        assertEquals(NotificationChannelType.EMAIL, saved.channel)
        assertEquals(NotificationType.PASSWORD_CHANGED, saved.type)
        assertEquals(email, saved.recipient)
        assertEquals(SUBJECT, saved.subject)
        assertEquals(customerId, saved.customerId)
        assertEquals(0, saved.attemptCount)

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
        assertEquals(NotificationStatus.SENT, sent.status)
        assertEquals(1, sent.attemptCount)
        assertNotNull(sent.sentAt)
        assertNull(sent.failureReason)
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
                assertEquals(NotificationStatus.PENDING, it.status)
                assertEquals(1, it.attemptCount)
                assertEquals("SMTP server unreachable", it.failureReason)
            }

            // Not due yet: the last attempt was just now.
            drainOutbox()
            assertEquals(1, row(notificationId).attemptCount)

            repeat(4) {
                backdate(notificationId)
                drainOutbox()
            }
            row(notificationId).let {
                assertEquals(NotificationStatus.FAILED, it.status)
                assertEquals(5, it.attemptCount)
                assertNull(it.sentAt)
            }

            // A FAILED row is never picked up again.
            backdate(notificationId)
            drainOutbox()
            assertEquals(5, row(notificationId).attemptCount)
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
            assertEquals(NotificationStatus.SENT, it.status)
            assertEquals(2, it.attemptCount)
            assertNull(it.failureReason)
        }
        assertEquals(1, testEmails(email).size)
    }

    companion object {
        private const val SUBJECT = "Test message"
    }
}
