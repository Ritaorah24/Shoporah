package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

/** Every test starts from the seed admin's first-run state (loginSeedAdmin resets it). */
class AdminAuthFlowTest : IntegrationTestBase() {

    private val newPassword = "changed123"

    @Test
    fun `seed admin login says the password must be changed`() {
        resetSeedAdmin()
        mockMvc.post("/api/admin/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to seedAdminEmail, "password" to seedAdminPassword)
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { isString() }
            jsonPath("$.mustChangePassword") { value(true) }
        }
    }

    @Test
    fun `admin endpoints are blocked until the password is changed`() {
        mockMvc.get("/api/admin/anything") {
            header("Authorization", "Bearer ${loginSeedAdmin()}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.message") { value("Password change required: call PATCH /api/admin/auth/password first") }
        }
    }

    @Test
    fun `changing the password returns a token that opens admin endpoints`() {
        val mustChangeToken = loginSeedAdmin()

        val result = mockMvc.patch("/api/admin/auth/password") {
            header("Authorization", "Bearer $mustChangeToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("currentPassword" to seedAdminPassword, "newPassword" to newPassword)
        }.andExpect {
            status { isOk() }
            jsonPath("$.mustChangePassword") { value(false) }
        }.andReturn()
        val newToken = readJson(result)["accessToken"].asString()

        // No admin endpoint exists yet at this URL: 404 means security let the request through.
        mockMvc.get("/api/admin/anything") {
            header("Authorization", "Bearer $newToken")
        }.andExpect { status { isNotFound() } }

        // The new password now works for login, and the old one does not.
        mockMvc.post("/api/admin/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to seedAdminEmail, "password" to newPassword)
        }.andExpect {
            status { isOk() }
            jsonPath("$.mustChangePassword") { value(false) }
        }
        mockMvc.post("/api/admin/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to seedAdminEmail, "password" to seedAdminPassword)
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `wrong current password returns 401`() {
        mockMvc.patch("/api/admin/auth/password") {
            header("Authorization", "Bearer ${loginSeedAdmin()}")
            contentType = MediaType.APPLICATION_JSON
            content = json("currentPassword" to "wrongpass1", "newPassword" to newPassword)
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.message") { value("Current password is incorrect") }
        }
    }

    @Test
    fun `new password equal to the current one returns 400`() {
        mockMvc.patch("/api/admin/auth/password") {
            header("Authorization", "Bearer ${loginSeedAdmin()}")
            contentType = MediaType.APPLICATION_JSON
            content = json("currentPassword" to seedAdminPassword, "newPassword" to seedAdminPassword)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("New password must be different from the current password") }
        }
    }

    @Test
    fun `weak new password returns 400 with field errors`() {
        mockMvc.patch("/api/admin/auth/password") {
            header("Authorization", "Bearer ${loginSeedAdmin()}")
            contentType = MediaType.APPLICATION_JSON
            content = json("currentPassword" to seedAdminPassword, "newPassword" to "lettersonly")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors[0].field") { value("newPassword") }
        }
    }

    @Test
    fun `change password without a token returns 401`() {
        mockMvc.patch("/api/admin/auth/password") {
            contentType = MediaType.APPLICATION_JSON
            content = json("currentPassword" to "anything1", "newPassword" to newPassword)
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `change password with a customer token returns 403`() {
        mockMvc.patch("/api/admin/auth/password") {
            header("Authorization", "Bearer ${newCustomerToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = json("currentPassword" to DEFAULT_PASSWORD, "newPassword" to newPassword)
        }.andExpect { status { isForbidden() } }
    }
}
