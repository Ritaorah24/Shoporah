package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

class CustomerProfileTest : IntegrationTestBase() {

    @Test
    fun `GET me returns the profile of the customer in the token`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)
        val token = loginCustomer(email)

        mockMvc.get("/api/customer/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.customerId") { value(customerId) }
            jsonPath("$.email") { value(email) }
        }
    }

    @Test
    fun `PATCH me changes only the fields sent`() {
        val email = uniqueEmail()
        registerCustomer(email)
        val token = loginCustomer(email)

        mockMvc.patch("/api/customer/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("firstName" to "Ada")
        }.andExpect {
            status { isOk() }
            jsonPath("$.firstName") { value("Ada") }
            jsonPath("$.lastName") { value("User") }
            jsonPath("$.phoneNumber") { value("+2348012345678") }
            jsonPath("$.email") { value(email) }
        }
    }

    @Test
    fun `PATCH me with email returns 400 and keeps the email`() {
        val email = uniqueEmail()
        registerCustomer(email)
        val token = loginCustomer(email)

        mockMvc.patch("/api/customer/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("firstName" to "Ada", "email" to "other@example.com")
        }.andExpect {
            status { isBadRequest() }
            content { string(containsString("email cannot be changed")) }
        }

        mockMvc.get("/api/customer/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            jsonPath("$.email") { value(email) }
            jsonPath("$.firstName") { value("Test") }
        }
    }

    @Test
    fun `PATCH me with nothing to update returns 400`() {
        mockMvc.patch("/api/customer/me") {
            header("Authorization", "Bearer ${newCustomerToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            content { string(containsString("send at least one of firstName, lastName, phoneNumber")) }
        }
    }

    @Test
    fun `PATCH me with a blank first name returns 400`() {
        mockMvc.patch("/api/customer/me") {
            header("Authorization", "Bearer ${newCustomerToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = json("firstName" to "   ")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors[0].field") { value("firstName") }
        }
    }

    @Test
    fun `GET me without a token returns 401`() {
        mockMvc.get("/api/customer/me").andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", containsString("Bearer")) }
            jsonPath("$.message") { value("Missing, invalid or expired access token") }
        }
    }

    @Test
    fun `GET me with an admin token returns 403`() {
        mockMvc.get("/api/customer/me") {
            header("Authorization", "Bearer ${loginSeedAdmin()}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.message") { value("You do not have permission to access this resource") }
        }
    }
}
