package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

class CustomerAuthFlowTest : IntegrationTestBase() {

    @Test
    fun `register returns 201 with the profile and never the password`() {
        val email = uniqueEmail()
        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = registerBody(email)
        }.andExpect {
            responseCode("00")
            jsonPath("$.data.customerId") { isNumber() }
            jsonPath("$.data.email") { value(email) }
            jsonPath("$.data.status") { value("ACTIVE") }
            jsonPath("$.data.password") { doesNotExist() }
            jsonPath("$.data.passwordHash") { doesNotExist() }
        }
    }

    @Test
    fun `register trims and lowercases the email`() {
        val email = uniqueEmail()
        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = registerBody("  ${email.uppercase()}  ")
        }.andExpect {
            responseCode("00")
            jsonPath("$.data.email") { value(email) }
        }
    }

    @Test
    fun `register rejects an email whose domain has no dot or no proper ending`() {
        val name = uniqueEmail().substringBefore("@")
        listOf("$name@gmailcom", "$name@localhost", "$name@example.c", "$name@example.123").forEach { email ->
            mockMvc.post("/api/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content = registerBody(email)
            }.andExpect {
                responseCode("106")
                jsonPath("$.data.fieldErrors.length()") { value(1) }
                jsonPath("$.data.fieldErrors[0].field") { value("email") }
                jsonPath("$.data.fieldErrors[0].message") { value("must be a well-formed email address") }
            }
        }
        val saved = jdbcClient.sql("SELECT COUNT(*) FROM public.ec_customer WHERE customeremail LIKE :prefix")
            .param("prefix", "$name@%").query(Int::class.javaObjectType).single()
        assertEquals(0, saved)

        // A subdomain and a country ending are fine.
        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = registerBody("$name.obi@mail.example.co.uk")
        }.andExpect { responseCode("00") }
    }

    @Test
    fun `register with an email already used returns 409`() {
        val email = uniqueEmail()
        registerCustomer(email)

        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = registerBody(email.uppercase())
        }.andExpect {
            responseCode("117")
            jsonPath("$.responseMessage") { value("Email is already registered") }
        }
    }

    @Test
    fun `register with a weak password returns 400 with field errors`() {
        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = registerBody(uniqueEmail(), password = "short")
        }.andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Validation failed") }
            jsonPath("$.data.fieldErrors[0].field") { value("password") }
            jsonPath("$.data.fieldErrors[*].rejectedValue") { doesNotExist() }
        }
    }

    @Test
    fun `login returns a bearer token`() {
        val email = uniqueEmail()
        registerCustomer(email)

        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to email, "password" to DEFAULT_PASSWORD)
        }.andExpect {
            responseCode("00")
            jsonPath("$.data.accessToken") { isString() }
            jsonPath("$.data.tokenType") { value("Bearer") }
            jsonPath("$.data.expiresIn") { value(3600) }
            jsonPath("$.data.mustChangePassword") { doesNotExist() }
        }
    }

    @Test
    fun `wrong password and unknown email give the same 401`() {
        val email = uniqueEmail()
        registerCustomer(email)

        val wrongPassword = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to email, "password" to "wrongpass1")
        }.andExpect { responseCode("86") }.andReturn()

        val unknownEmail = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to uniqueEmail(), "password" to "wrongpass1")
        }.andExpect { responseCode("86") }.andReturn()

        // Same body apart from the timestamp, so login cannot reveal which emails exist.
        val first = readJson(wrongPassword)
        val second = readJson(unknownEmail)
        assertEquals("Invalid email or password", first["responseMessage"].asString())
        assertEquals(first["responseMessage"], second["responseMessage"])
        assertEquals(first["error"], second["error"])
    }

    @Test
    fun `a suspended customer gets 403 with the right password and 401 with a wrong one`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)
        jdbcClient.sql("UPDATE public.ec_customer SET customerstatus = 'SUSPENDED' WHERE customerid = :id")
            .param("id", customerId)
            .update()

        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to email, "password" to DEFAULT_PASSWORD)
        }.andExpect {
            responseCode("107")
            jsonPath("$.responseMessage") { value("Account is not active") }
        }

        // Status is only revealed after a correct password.
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to email, "password" to "wrongpass1")
        }.andExpect { responseCode("86") }
    }
}
