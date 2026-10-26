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
            status { isCreated() }
            jsonPath("$.customerId") { isNumber() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.password") { doesNotExist() }
            jsonPath("$.passwordHash") { doesNotExist() }
        }
    }

    @Test
    fun `register trims and lowercases the email`() {
        val email = uniqueEmail()
        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = registerBody("  ${email.uppercase()}  ")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.email") { value(email) }
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
                status { isBadRequest() }
                jsonPath("$.fieldErrors.length()") { value(1) }
                jsonPath("$.fieldErrors[0].field") { value("email") }
                jsonPath("$.fieldErrors[0].message") { value("must be a well-formed email address") }
            }
        }
        val saved = jdbcClient.sql("SELECT COUNT(*) FROM public.ec_customer WHERE customeremail LIKE :prefix")
            .param("prefix", "$name@%").query(Int::class.javaObjectType).single()
        assertEquals(0, saved)

        // A subdomain and a country ending are fine.
        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = registerBody("$name.obi@mail.example.co.uk")
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `register with an email already used returns 409`() {
        val email = uniqueEmail()
        registerCustomer(email)

        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = registerBody(email.uppercase())
        }.andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("Email is already registered") }
        }
    }

    @Test
    fun `register with a weak password returns 400 with field errors`() {
        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = registerBody(uniqueEmail(), password = "short")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Validation failed") }
            jsonPath("$.fieldErrors[0].field") { value("password") }
            jsonPath("$.fieldErrors[*].rejectedValue") { doesNotExist() }
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
            status { isOk() }
            jsonPath("$.accessToken") { isString() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiresIn") { value(3600) }
            jsonPath("$.mustChangePassword") { doesNotExist() }
        }
    }

    @Test
    fun `wrong password and unknown email give the same 401`() {
        val email = uniqueEmail()
        registerCustomer(email)

        val wrongPassword = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to email, "password" to "wrongpass1")
        }.andExpect { status { isUnauthorized() } }.andReturn()

        val unknownEmail = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to uniqueEmail(), "password" to "wrongpass1")
        }.andExpect { status { isUnauthorized() } }.andReturn()

        // Same body apart from the timestamp, so login cannot reveal which emails exist.
        val first = readJson(wrongPassword)
        val second = readJson(unknownEmail)
        assertEquals("Invalid email or password", first["message"].asString())
        assertEquals(first["message"], second["message"])
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
            status { isForbidden() }
            jsonPath("$.message") { value("Account is not active") }
        }

        // Status is only revealed after a correct password.
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to email, "password" to "wrongpass1")
        }.andExpect { status { isUnauthorized() } }
    }
}
