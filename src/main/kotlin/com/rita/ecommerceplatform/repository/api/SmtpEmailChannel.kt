package com.rita.ecommerceplatform.repository.api

import com.rita.ecommerceplatform.model.entity.NotificationChannelType
import com.rita.ecommerceplatform.repository.api.NotificationChannel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/**
 * Plain-text email over SMTP (spring.mail.* settings; Mailtrap's sandbox in development).
 * Email is not essential to the store, so the app starts without MAIL_HOST: queued emails then
 * fail with a clear reason instead of stopping startup.
 */
@Component
class SmtpEmailChannel(
    private val mailSender: ObjectProvider<JavaMailSender>,
    @Value("\${spring.mail.host:}") private val host: String,
    @Value("\${app.notifications.from}") private val from: String,
) : NotificationChannel {

    private val log = LoggerFactory.getLogger(SmtpEmailChannel::class.java)

    init {
        if (host.isBlank()) log.warn("MAIL_HOST is not set: emails will not be sent")
    }

    override val type = NotificationChannelType.EMAIL

    override fun send(recipient: String, subject: String?, body: String?) {
        if (host.isBlank()) throw IllegalStateException(NOT_CONFIGURED)
        val sender = mailSender.ifAvailable ?: throw IllegalStateException(NOT_CONFIGURED)
        val message = SimpleMailMessage().apply {
            setFrom(from)
            setTo(recipient)
            setSubject(subject)
            setText(body)
        }
        sender.send(message)
    }

    companion object {
        const val NOT_CONFIGURED = "Email is not configured (MAIL_HOST is not set)"
    }
}
