package com.rita.ecommerceplatform.support

import com.rita.ecommerceplatform.model.NotificationChannelType
import com.rita.ecommerceplatform.repository.api.NotificationChannel
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for SmtpEmailChannel in every integration test (imported by IntegrationTestBase, @Primary),
 * so no test sends real email. It records what it was asked to send; set failWith to make it throw.
 */
@TestComponent
@Primary
class FakeEmailChannel : NotificationChannel {

    data class SentEmail(val recipient: String, val subject: String?, val body: String?)

    private val sentEmails = CopyOnWriteArrayList<SentEmail>()

    /** While set, every send throws with this message, like a broken mail server. Reset it in a finally block. */
    @Volatile
    var failWith: String? = null

    override val type = NotificationChannelType.EMAIL

    /** Every email "sent" so far; filter by recipient, which is unique per test. */
    val sent: List<SentEmail> get() = sentEmails.toList()

    fun sentTo(recipient: String): List<SentEmail> = sentEmails.filter { it.recipient == recipient }

    override fun send(recipient: String, subject: String?, body: String?) {
        failWith?.let { throw IllegalStateException(it) }
        sentEmails += SentEmail(recipient, subject, body)
    }
}
