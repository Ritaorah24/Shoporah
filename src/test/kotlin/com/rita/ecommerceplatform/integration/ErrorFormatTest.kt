package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

/** Spring MVC's own errors come back in the same BaseResponse shape as ours. */
class ErrorFormatTest : IntegrationTestBase() {

    private fun ResultActionsDsl.expectError(code: String, message: String) = andExpect {
        responseCode(code)
        jsonPath("$.responseMessage") { value(message) }
        jsonPath("$.data") { doesNotExist() }
    }

    @Test
    fun `unknown URL returns 404`() {
        mockMvc.get("/api/customer/nothing") {
            header("Authorization", "Bearer ${newCustomerToken()}")
        }.expectError("25", "No endpoint found for this URL")
    }

    @Test
    fun `unsupported method returns 405 with the Allow header`() {
        mockMvc.delete("/api/customer/me") {
            header("Authorization", "Bearer ${newCustomerToken()}")
        }.expectError("106", "HTTP method DELETE is not supported for this URL")
            .andExpect { header { string("Allow", containsString("GET")) } }
    }

    @Test
    fun `wrong content type returns 415`() {
        mockMvc.patch("/api/customer/me") {
            header("Authorization", "Bearer ${newCustomerToken()}")
            contentType = MediaType.TEXT_PLAIN
            content = "hello"
        }.expectError("106", "Content-Type must be application/json")
    }

    @Test
    fun `malformed JSON returns 400`() {
        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"firstName":"""
        }.expectError("106", "Malformed or unreadable JSON request body")
    }
}
