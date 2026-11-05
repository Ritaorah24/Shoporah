package com.rita.ecommerceplatform.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Background threads for emails that are sent straight away instead of through the outbox
 * (password reset links, whose token must never be stored). The request answers without waiting
 * for the mail server, so its response time does not reveal whether the email exists.
 */
@Configuration
class AsyncConfig {

    @Bean("taskExecutor")
    fun taskExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 2
        queueCapacity = 100
        setThreadNamePrefix("email-")
        // On shutdown, finish the emails already handed over instead of dropping them.
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(15)
    }
}
