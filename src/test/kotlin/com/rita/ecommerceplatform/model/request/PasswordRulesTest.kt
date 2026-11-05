package com.rita.ecommerceplatform.model.request

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D8 password rules, checked with the validator only: no Spring, no database. */
class PasswordRulesTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private fun registerPasswordValid(password: String): Boolean {
        val request = RegisterRequest(
            firstName = "Test",
            lastName = "User",
            email = "test@example.com",
            phoneNumber = "+2348012345678",
            password = password,
        )
        return validator.validate(request).none { it.propertyPath.toString() == "password" }
    }

    private fun newPasswordValid(password: String): Boolean =
        validator.validate(ChangePasswordRequest(currentPassword = "anything1", newPassword = password))
            .none { it.propertyPath.toString() == "newPassword" }

    @Test
    fun `letters and numbers, 8 to 72 characters, is accepted`() {
        assertTrue(registerPasswordValid("secret123"))
        assertTrue(registerPasswordValid("a1" + "x".repeat(70))) // exactly 72
        assertTrue(newPasswordValid("changed123"))
    }

    @Test
    fun `too short is rejected`() {
        assertFalse(registerPasswordValid("abc1234")) // 7
        assertFalse(newPasswordValid("abc1234"))
    }

    @Test
    fun `more than 72 characters is rejected because BCrypt ignores the rest`() {
        assertFalse(registerPasswordValid("a1" + "x".repeat(71))) // 73
        assertFalse(newPasswordValid("a1" + "x".repeat(71)))
    }

    @Test
    fun `letters only or numbers only is rejected`() {
        assertFalse(registerPasswordValid("lettersonly"))
        assertFalse(registerPasswordValid("12345678"))
        assertFalse(newPasswordValid("lettersonly"))
        assertFalse(newPasswordValid("12345678"))
    }

    @Test
    fun `blank is rejected`() {
        assertFalse(registerPasswordValid("        "))
        assertFalse(newPasswordValid(""))
    }
}
