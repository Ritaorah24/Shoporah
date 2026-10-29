package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Admin customer list and details (checkpoint 2.2, Step 7b). Order states are set directly in the database:
 * only the counts are tested here.
 */
class AdminCustomerTest : IntegrationTestBase() {

    private lateinit var adminToken: String

    // A last name unique to this test, so the search only finds this test's customers.
    private lateinit var lastName: String

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        lastName = "Cust" + UUID.randomUUID().toString().filter { it.isLetter() }.take(6)
    }

    private fun register(firstName: String, phone: String = "+2348012345678"): Pair<Long, String> {
        val email = uniqueEmail()
        val id = readJson(
            mockMvc.post("/api/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content = json(
                    "firstName" to firstName,
                    "lastName" to lastName,
                    "email" to email,
                    "phoneNumber" to phone,
                    "password" to DEFAULT_PASSWORD,
                )
            }.andExpect { status { isCreated() } }.andReturn(),
        )["customerId"].asLong()
        return id to email
    }

    // A bare order row in the given state: only the customer's counts read it.
    private fun insertOrder(customerId: Long, total: Int, paymentStatus: String) {
        jdbcClient.sql(
            """
            INSERT INTO public.ec_order (
                orderreference, orderidempotencykey, ordercustomerid, orderdeliverymethodid,
                orderrecipientname, orderrecipientphonenumber, orderaddressline1, ordercity, orderstate, ordercountry,
                ordersubtotal, orderdeliveryfee, orderdiscountamount, ordertotal, orderstatus, orderpaymentstatus
            )
            SELECT :reference, :key, :customerId, deliverymethodid,
                   'Ada Obi', '08012345678', '12 Allen Avenue', 'Ikeja', 'Lagos', 'Nigeria',
                   :total, 0, 0, :total, :status, :paymentStatus
            FROM public.ec_delivery_method WHERE deliverymethodcode = 'STANDARD'
            """
        )
            .param("reference", "ORD-TEST-${UUID.randomUUID().toString().take(8)}")
            .param("key", UUID.randomUUID())
            .param("customerId", customerId)
            .param("total", total)
            .param("status", if (paymentStatus == "UNPAID") "PENDING" else "DELIVERED")
            .param("paymentStatus", paymentStatus)
            .update()
    }

    private fun list(vararg params: Pair<String, String>): JsonNode = readJson(
        mockMvc.get("/api/admin/customers") {
            header("Authorization", "Bearer $adminToken")
            params.forEach { (name, value) -> param(name, value) }
        }.andExpect { status { isOk() } }.andReturn(),
    )

    private fun ids(vararg params: Pair<String, String>): List<Long> =
        list(*params)["content"].toList().map { it["customerId"].asLong() }

    @Test
    fun `search matches email, full name or phone, newest first, with each customer's order count`() {
        val (ada, adaEmail) = register("Ada")
        val (bola, _) = register("Bola", phone = "+2348099990000")
        insertOrder(ada, 1000, "UNPAID")
        insertOrder(ada, 2000, "PAID")

        assertEquals(listOf(bola, ada), ids("search" to lastName))
        assertEquals(listOf(ada), ids("search" to adaEmail.uppercase()))
        assertEquals(listOf(ada), ids("search" to "ada ${lastName.lowercase()}"))
        assertEquals(listOf(bola), ids("search" to "99990000", "status" to "ACTIVE"))

        val rows = list("search" to lastName)["content"].toList().associateBy { it["customerId"].asLong() }
        assertEquals(2, rows.getValue(ada)["orderCount"].asInt())
        assertEquals(0, rows.getValue(bola)["orderCount"].asInt())
        assertEquals("Bola", rows.getValue(bola)["firstName"].asString())
        assertEquals("ACTIVE", rows.getValue(bola)["status"].asString())
    }

    @Test
    fun `status filters and pages work`() {
        val (first, _) = register("Ada")
        val (second, _) = register("Bola")
        val (suspended, _) = register("Chi")
        jdbcClient.sql("UPDATE public.ec_customer SET customerstatus = 'SUSPENDED' WHERE customerid = :id")
            .param("id", suspended).update()

        assertEquals(listOf(suspended), ids("search" to lastName, "status" to "suspended"))
        assertEquals(listOf(second, first), ids("search" to lastName, "status" to "ACTIVE"))

        val page = list("search" to lastName, "size" to "2", "page" to "1")
        assertEquals(3, page["totalElements"].asInt())
        assertEquals(2, page["totalPages"].asInt())
        assertEquals(listOf(first), page["content"].toList().map { it["customerId"].asLong() })
    }

    @Test
    fun `the detail counts orders and what the customer spent, without refunded orders`() {
        val (ada, adaEmail) = register("Ada")
        insertOrder(ada, 1000, "UNPAID")
        insertOrder(ada, 2500, "PAID")
        insertOrder(ada, 4000, "PAID")
        insertOrder(ada, 9000, "REFUNDED")

        mockMvc.get("/api/admin/customers/$ada") { header("Authorization", "Bearer $adminToken") }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value(adaEmail) }
            jsonPath("$.firstName") { value("Ada") }
            jsonPath("$.orderCount") { value(4) }
            jsonPath("$.paidOrderCount") { value(2) }
            jsonPath("$.totalSpent") { value(6500.0) }
        }

        val (newcomer, _) = register("Bola")
        mockMvc.get("/api/admin/customers/$newcomer") { header("Authorization", "Bearer $adminToken") }.andExpect {
            jsonPath("$.orderCount") { value(0) }
            jsonPath("$.totalSpent") { value(0) }
        }
    }

    @Test
    fun `no secrets are returned, unknown customers are 404, bad status is 400 and customers are refused`() {
        val (ada, _) = register("Ada")
        val body = mockMvc.get("/api/admin/customers/$ada") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        listOf("password", "hash", "token").forEach { assertFalse(body.contains(it, ignoreCase = true), body) }

        mockMvc.get("/api/admin/customers/999999999") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isNotFound() } }
        mockMvc.get("/api/admin/customers") {
            header("Authorization", "Bearer $adminToken")
            param("status", "GONE")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("status must be one of ACTIVE, SUSPENDED, DEACTIVATED, DELETED") }
        }

        val customerToken = newCustomerToken()
        mockMvc.get("/api/admin/customers") { header("Authorization", "Bearer $customerToken") }
            .andExpect { status { isForbidden() } }
        mockMvc.get("/api/admin/customers/$ada") { header("Authorization", "Bearer $customerToken") }
            .andExpect { status { isForbidden() } }
    }
}
