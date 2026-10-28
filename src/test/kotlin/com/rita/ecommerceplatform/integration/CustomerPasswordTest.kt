package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CustomerPasswordTest : IntegrationTestBase() {

    private fun changePassword(token: String, currentPassword: String?, newPassword: String?) =
        mockMvc.patch("/api/customer/me/password") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("currentPassword" to currentPassword, "newPassword" to newPassword)
        }

    private fun login(email: String, password: String) =
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to email, "password" to password)
        }

    private fun passwordChangedAt(customerId: Long): LocalDateTime? =
        jdbcClient.sql("SELECT customerauthpasswordchangedat FROM public.ec_customer_auth WHERE customerauthcustomerid = :id")
            .param("id", customerId)
            .query(LocalDateTime::class.java)
            .optional()
            .orElse(null)

    @Test
    fun `change password returns 204 and only the new password logs in`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)
        assertNull(passwordChangedAt(customerId))

        changePassword(loginCustomer(email), DEFAULT_PASSWORD, "newSecret456").andExpect {
            status { isNoContent() }
            content { string("") }
        }

        login(email, "newSecret456").andExpect { status { isOk() } }
        login(email, DEFAULT_PASSWORD).andExpect { status { isUnauthorized() } }
        assertNotNull(passwordChangedAt(customerId))
    }

    @Test
    fun `a wrong current password returns 401 and keeps the password`() {
        val email = uniqueEmail()
        registerCustomer(email)

        changePassword(loginCustomer(email), "wrongpass1", "newSecret456").andExpect {
            status { isUnauthorized() }
            jsonPath("$.message") { value("Current password is incorrect") }
        }

        login(email, DEFAULT_PASSWORD).andExpect { status { isOk() } }
    }

    @Test
    fun `the same password as the current one returns 400`() {
        changePassword(newCustomerToken(), DEFAULT_PASSWORD, DEFAULT_PASSWORD).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("New password must be different from the current password") }
        }
    }

    @Test
    fun `a weak new password returns 400 with a newPassword field error`() {
        val token = newCustomerToken()
        listOf("short1", "abcdefgh", "12345678").forEach { weak ->
            changePassword(token, DEFAULT_PASSWORD, weak).andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[*].field") { value(hasItem("newPassword")) }
            }
        }
    }

    @Test
    fun `missing fields return 400 with both field errors`() {
        changePassword(newCustomerToken(), null, null).andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors[*].field") { value(hasItem("currentPassword")) }
            jsonPath("$.fieldErrors[*].field") { value(hasItem("newPassword")) }
        }
    }

    @Test
    fun `a suspended customer with the right password gets 403`() {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)
        val token = loginCustomer(email)
        jdbcClient.sql("UPDATE public.ec_customer SET customerstatus = 'SUSPENDED' WHERE customerid = :id")
            .param("id", customerId)
            .update()

        changePassword(token, DEFAULT_PASSWORD, "newSecret456").andExpect {
            status { isForbidden() }
            jsonPath("$.message") { value("Account is not active") }
        }
    }

    @Test
    fun `an admin token gets 403 and no token gets 401`() {
        changePassword(loginSeedAdmin(), DEFAULT_PASSWORD, "newSecret456").andExpect { status { isForbidden() } }

        mockMvc.patch("/api/customer/me/password") {
            contentType = MediaType.APPLICATION_JSON
            content = json("currentPassword" to DEFAULT_PASSWORD, "newPassword" to "newSecret456")
        }.andExpect { status { isUnauthorized() } }
    }
}
