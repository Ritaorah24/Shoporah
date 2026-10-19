package com.rita.ecommerceplatform.service

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Order references like ORD-20261019-K7Q2M9XA (O3): the order date (UTC+1, the platform's time
 * convention) and 8 random characters. The alphabet leaves out look-alikes (0/O, 1/I/L), so a
 * customer can read a reference over the phone. Uniqueness is checked by OrderService.
 */
@Component
class OrderReferenceGenerator {

    private val random = SecureRandom()

    fun generate(today: LocalDate = LocalDate.now(PLATFORM_ZONE)): String {
        val suffix = (1..RANDOM_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "ORD-${today.format(DATE_FORMAT)}-$suffix"
    }

    companion object {
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        const val RANDOM_LENGTH = 8
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val PLATFORM_ZONE: ZoneOffset = ZoneOffset.ofHours(1)
    }
}
