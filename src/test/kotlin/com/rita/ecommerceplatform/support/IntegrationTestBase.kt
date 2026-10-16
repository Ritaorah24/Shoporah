package com.rita.ecommerceplatform.support

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Parent of every integration test: the whole app (security, controllers, services, SQL)
 * against ecommerce_test, called through MockMvc without opening a port.
 * All subclasses share one Spring context, so the database is reset once per run; tests
 * therefore use unique emails instead of relying on empty tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseReset::class)
abstract class IntegrationTestBase {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var jsonMapper: JsonMapper

    @Autowired
    protected lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Value("\${spring.flyway.placeholders.seed_admin_email}")
    protected lateinit var seedAdminEmail: String

    @Value("\${spring.flyway.placeholders.seed_admin_password}")
    protected lateinit var seedAdminPassword: String

    protected fun uniqueEmail(): String = "t-${UUID.randomUUID().toString().take(8)}@example.com"

    protected fun json(vararg fields: Pair<String, Any?>): String = jsonMapper.writeValueAsString(mapOf(*fields))

    protected fun readJson(result: MvcResult): JsonNode = jsonMapper.readTree(result.response.contentAsString)

    protected fun registerBody(email: String, password: String = DEFAULT_PASSWORD): String = json(
        "firstName" to "Test",
        "lastName" to "User",
        "email" to email,
        "phoneNumber" to "+2348012345678",
        "password" to password,
    )

    /** Registers a customer and returns the new customerId. */
    protected fun registerCustomer(email: String = uniqueEmail(), password: String = DEFAULT_PASSWORD): Long {
        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = registerBody(email, password)
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["customerId"].asLong()
    }

    /** Logs a customer in and returns the access token. */
    protected fun loginCustomer(email: String, password: String = DEFAULT_PASSWORD): String {
        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to email, "password" to password)
        }.andExpect { status { isOk() } }.andReturn()
        return readJson(result)["accessToken"].asString()
    }

    /** A fresh customer, logged in. */
    protected fun newCustomerToken(): String = uniqueEmail().let { email ->
        registerCustomer(email)
        loginCustomer(email)
    }

    /** Puts the seed admin back to its first-run state: seed password, MUST_CHANGE. */
    protected fun resetSeedAdmin() {
        jdbcClient.sql(
            """
            UPDATE public.ec_admin_auth
            SET adminauthpasswordhash = :hash, adminauthpasswordstatus = 'MUST_CHANGE'
            WHERE adminauthadminid = (SELECT adminid FROM public.ec_admin WHERE adminemail = :email)
            """.trimIndent(),
        )
            .param("hash", passwordEncoder.encode(seedAdminPassword))
            .param("email", seedAdminEmail)
            .update()
    }

    /** Resets the seed admin, logs in with the seed password and returns the (MUST_CHANGE) token. */
    protected fun loginSeedAdmin(): String {
        resetSeedAdmin()
        val result = mockMvc.post("/api/admin/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to seedAdminEmail, "password" to seedAdminPassword)
        }.andExpect { status { isOk() } }.andReturn()
        return readJson(result)["accessToken"].asString()
    }

    /**
     * Resets the seed admin, changes its password and returns the new token: the state needed to use
     * admin endpoints, since MUST_CHANGE blocks everything under /api/admin except the password change.
     */
    protected fun loginActiveAdmin(): String {
        val result = mockMvc.patch("/api/admin/auth/password") {
            header("Authorization", "Bearer ${loginSeedAdmin()}")
            contentType = MediaType.APPLICATION_JSON
            content = json("currentPassword" to seedAdminPassword, "newPassword" to CHANGED_ADMIN_PASSWORD)
        }.andExpect { status { isOk() } }.andReturn()
        return readJson(result)["accessToken"].asString()
    }

    /** The seed admin's id, for inserting rows that need a creating admin. */
    protected fun seedAdminId(): Int =
        jdbcClient.sql("SELECT adminid FROM public.ec_admin WHERE adminemail = :email")
            .param("email", seedAdminEmail)
            .query(Int::class.javaObjectType)
            .single()

    companion object {
        const val DEFAULT_PASSWORD = "secret123"
        const val CHANGED_ADMIN_PASSWORD = "changed123"
    }
}
