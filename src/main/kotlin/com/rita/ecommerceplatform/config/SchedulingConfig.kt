package com.rita.ecommerceplatform.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Runs @Scheduled jobs (the notification sender). Off in tests (app.notifications.sender.enabled=false),
 * which call NotificationSender.sendDue themselves so results never depend on timing.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["app.notifications.sender.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfig
