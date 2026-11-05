package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.service.PasswordResetService
import com.rita.ecommerceplatform.support.FakeEmailChannel
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PasswordResetTest : IntegrationTestBase() {

    private fun forgot(email: String) =
        mockMvc.post("/dev/auth/forgot-password") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to email)
        }

    private fun reset(token: String?, newPassword: String?) =
        mockMvc.post("/dev/auth/reset-password") {
            contentType = MediaType.APPLICATION_JSON
            content = json("token" to token, "newPassword" to newPassword)
        }

    private fun login(email: String, password: String) =
        mockMvc.post("/dev/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to email, "password" to password)
        }

    private fun forgotAccepted(email: String) {
        forgot(email).andExpect {
            responseCode("00")
            jsonPath("$.responseMessage") { value(PasswordResetService.FORGOT_REPLY) }
        }
    }

    // The email is sent on a background thread, so wait (up to 5 s) for it to arrive.
    private fun awaitEmails(email: String, count: Int): List<FakeEmailChannel.SentEmail> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val sent = emailChannel.sentTo(email)
            if (sent.size >= count) return sent
            Thread.sleep(50)
        }
        fail("Expected $count email(s) to $email, got ${emailChannel.sentTo(email).size}")
    }

    // For "no email" checks: give a background send the chance to happen, then check it did not.
    private fun assertNoEmail(email: String) {
        Thread.sleep(500)
        assertEquals(0, emailChannel.sentTo(email).size)
    }

    private fun tokenFrom(email: FakeEmailChannel.SentEmail): String =
        Regex("token=([A-Za-z0-9_-]+)").find(email.body!!)?.groupValues?.get(1) ?: fail("No token in: ${email.body}")

    private fun resetColumns(customerId: Long): Pair<String?, Any?> =
        jdbcClient.sql(
            "SELECT customerauthresettokenhash, customerauthresettokenexpiresat FROM public.ec_customer_auth WHERE customerauthcustomerid = :id"
        )
            .param("id", customerId)
            .query { rs, _ -> rs.getString(1) to rs.getObject(2) }
            .single()

    private fun moveTokenExpiry(customerId: Long, interval: String) {
        jdbcClient.sql(
            "UPDATE public.ec_customer_auth SET customerauthresettokenexpiresat = customerauthresettokenexpiresat - INTERVAL '$interval' WHERE customerauthcustomerid = :id"
        )
            .param("id", customerId)
            .update()
    }

    @Test
    fun `the emailed token resets the password once, and only its hash is stored`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)

        forgotAccepted(email)
        val sent = awaitEmails(email, 1).single()
        assertEquals("Reset your Shoporah password", sent.subject)
        val token = tokenFrom(sent)
        assertTrue(sent.body!!.contains("?token=$token"))

        val (hash, expiresAt) = resetColumns(customerId)
        assertNotNull(hash)
        assertNotNull(expiresAt)
        assertFalse(hash.contains(token), "the raw token must not be stored")
        assertEquals(64, hash.length) // SHA-256 in hex

        reset(token, "brandNew789").andExpect { responseCode("00") }
        login(email, "brandNew789").andExpect { responseCode("00") }
        login(email, DEFAULT_PASSWORD).andExpect { responseCode("86") }
        assertEquals(null to null, resetColumns(customerId))

        // Single use.
        reset(token, "another789").andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Reset link is invalid or has expired") }
        }
        login(email, "brandNew789").andExpect { responseCode("00") }
    }

    @Test
    fun `the reset is logged without the token`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)

        forgotAccepted(email)
        val token = tokenFrom(awaitEmails(email, 1).single())

        // The log row is written just after the send, on the same background thread.
        val deadline = System.currentTimeMillis() + 5_000
        var rows: List<Triple<String, String, String>> = emptyList()
        while (System.currentTimeMillis() < deadline && rows.isEmpty()) {
            rows = jdbcClient.sql(
                """
                SELECT notificationtype::text, notificationstatus::text, notificationbody
                FROM public.ec_notification WHERE notificationcustomerid = :id AND notificationtype = 'PASSWORD_RESET'
                """
            )
                .param("id", customerId)
                .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }
                .list()
            if (rows.isEmpty()) Thread.sleep(50)
        }
        val row = rows.single()
        assertEquals("PASSWORD_RESET", row.first)
        assertEquals("SENT", row.second)
        assertEquals(PasswordResetService.LOG_BODY, row.third)
        assertFalse(row.third.contains(token))
    }

    @Test
    fun `an expired token is rejected and the password stays`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)
        forgotAccepted(email)
        val token = tokenFrom(awaitEmails(email, 1).single())

        moveTokenExpiry(customerId, "31 minutes")

        reset(token, "brandNew789").andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Reset link is invalid or has expired") }
        }
        login(email, DEFAULT_PASSWORD).andExpect { responseCode("00") }
    }

    @Test
    fun `a made-up token is rejected`() {
        reset("not-a-real-token", "brandNew789").andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Reset link is invalid or has expired") }
        }
    }

    @Test
    fun `a new request within the cooldown sends nothing, and after it the new link replaces the old one`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)

        forgotAccepted(email)
        val firstToken = tokenFrom(awaitEmails(email, 1).single())

        // Within 60 s: same reply, no second email, the first link still works.
        forgotAccepted(email)
        Thread.sleep(500)
        assertEquals(1, emailChannel.sentTo(email).size)

        // Past the cooldown: a new link, and the old one stops working.
        moveTokenExpiry(customerId, "2 minutes")
        forgotAccepted(email)
        val secondToken = tokenFrom(awaitEmails(email, 2)[1])
        assertTrue(firstToken != secondToken)

        reset(firstToken, "brandNew789").andExpect { responseCode("106") }
        reset(secondToken, "brandNew789").andExpect { responseCode("00") }
    }

    @Test
    fun `unknown and suspended emails get the same reply and no email`() {
        val unknown = uniqueEmail()
        forgotAccepted(unknown)

        val suspended = uniqueEmail()
        val customerId = registerCustomer(suspended)
        jdbcClient.sql("UPDATE public.ec_customer SET customerstatus = 'SUSPENDED' WHERE customerid = :id")
            .param("id", customerId)
            .update()
        forgotAccepted(suspended)

        assertNoEmail(unknown)
        assertNoEmail(suspended)
        assertNull(resetColumns(customerId).first)
    }

    @Test
    fun `a customer suspended after getting the link gets 403`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)
        forgotAccepted(email)
        val token = tokenFrom(awaitEmails(email, 1).single())

        jdbcClient.sql("UPDATE public.ec_customer SET customerstatus = 'SUSPENDED' WHERE customerid = :id")
            .param("id", customerId)
            .update()

        reset(token, "brandNew789").andExpect {
            responseCode("107")
            jsonPath("$.responseMessage") { value("Account is not active") }
        }
    }

    @Test
    fun `bad input returns 400 with field errors`() {
        forgot("ada@gmailcom").andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].field") { value("email") }
        }
        reset("  ", "brandNew789").andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[*].field") { value(hasItem("token")) }
        }
        reset("some-token", "abcdefgh").andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[*].field") { value(hasItem("newPassword")) }
        }
    }
}
