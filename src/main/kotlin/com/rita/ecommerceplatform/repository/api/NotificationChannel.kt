package com.rita.ecommerceplatform.repository.api

import com.rita.ecommerceplatform.model.NotificationChannelType

/**
 * A way of delivering a message to a person (checkpoint 2.1). SmtpEmailChannel is the only one so far;
 * SMS, push or in-app would be further implementations (and further values of ck__ec_notification__channel).
 * Tests replace the email channel with a fake, so no test sends real email.
 * Any failure is thrown; NotificationSender records it on the row and retries later.
 */
interface NotificationChannel {

    val type: NotificationChannelType

    fun send(recipient: String, subject: String?, body: String?)
}
