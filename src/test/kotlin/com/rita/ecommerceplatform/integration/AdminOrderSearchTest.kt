package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Order search filters on GET /dev/admin/orders (checkpoint 2.2, Step 7b). Statuses are set directly in the
 * database where a test only needs an order in that state: the payment and cancel flows have their own tests.
 */
class AdminOrderSearchTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private var categoryId: Int = 0

    // A last name unique to this test, so its customers can be searched for without other tests' data.
    private lateinit var lastName: String

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        categoryId = createCategory()
        lastName = "Srch" + UUID.randomUUID().toString().filter { it.isLetter() }.take(6)
    }

    private data class Buyer(val customerId: Long, val email: String, val token: String)

    private data class Placed(val orderId: Long, val reference: String, val createdAt: LocalDateTime)

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createCategory(): Int {
        val result = mockMvc.post("/dev/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun buyer(firstName: String): Buyer {
        val email = uniqueEmail()
        val customerId = readJson(
            mockMvc.post("/dev/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content = json(
                    "firstName" to firstName,
                    "lastName" to lastName,
                    "email" to email,
                    "phoneNumber" to "+2348012345678",
                    "password" to DEFAULT_PASSWORD,
                )
            }.andExpect { responseCode("00") }.andReturn(),
        )["customerId"].asLong()
        val token = loginCustomer(email)
        mockMvc.post("/dev/customer/addresses") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "recipientName" to "$firstName $lastName",
                "recipientPhoneNumber" to "08012345678",
                "addressLine1" to "12 Allen Avenue",
                "city" to "Ikeja",
                "state" to "Lagos",
                "country" to "Nigeria",
            )
        }.andExpect { responseCode("00") }
        return Buyer(customerId, email, token)
    }

    private fun placeOrder(buyer: Buyer): Placed {
        val productId = readJson(
            mockMvc.post("/dev/admin/products") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = json("name" to unique("Phone"), "sku" to unique("sku"), "categoryId" to categoryId, "price" to 1000, "stockQuantity" to 10)
            }.andExpect { responseCode("00") }.andReturn(),
        )["productId"].asLong()
        mockMvc.post("/dev/customer/cart/items") {
            header("Authorization", "Bearer ${buyer.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to productId, "quantity" to 1)
        }.andExpect { responseCode("00") }
        val order = readJson(
            mockMvc.post("/dev/customer/orders") {
                header("Authorization", "Bearer ${buyer.token}")
                header("Idempotency-Key", UUID.randomUUID().toString())
            }.andExpect { responseCode("00") }.andReturn(),
        )
        return Placed(order["orderId"].asLong(), order["reference"].asString(), LocalDateTime.parse(order["createdAt"].asString()))
    }

    private fun setState(orderId: Long, status: String, paymentStatus: String) {
        jdbcClient.sql("UPDATE public.ec_order SET orderstatus = :s, orderpaymentstatus = :p WHERE orderid = :id")
            .param("s", status).param("p", paymentStatus).param("id", orderId).update()
    }

    private fun search(vararg params: Pair<String, String>): JsonNode = readJson(
        mockMvc.get("/dev/admin/orders") {
            header("Authorization", "Bearer $adminToken")
            params.forEach { (name, value) -> param(name, value) }
        }.andExpect { responseCode("00") }.andReturn(),
    )

    private fun ids(vararg params: Pair<String, String>): List<Long> =
        search(*params)["content"].toList().map { it["orderId"].asLong() }

    @Test
    fun `a full reference finds its order in any case, and a prefix finds every order starting with it`() {
        val ada = buyer("Ada")
        val first = placeOrder(ada)
        val second = placeOrder(ada)

        assertEquals(listOf(first.orderId), ids("reference" to first.reference))
        assertEquals(listOf(first.orderId), ids("reference" to first.reference.lowercase()))
        // ORD-yyyyMMdd-: both were placed today; newest first.
        val dayPrefix = first.reference.substringBeforeLast("-") + "-"
        assertEquals(listOf(second.orderId, first.orderId), ids("reference" to dayPrefix, "customerId" to ada.customerId.toString()))
        assertEquals(emptyList(), ids("reference" to "ORD-19990101"))
        // Wildcards in the text are literal: "%" matches no reference.
        assertEquals(emptyList(), ids("reference" to "%"))
    }

    @Test
    fun `customerId and customer text find one customer's orders`() {
        val ada = buyer("Ada")
        val bola = buyer("Bola")
        val adaOrder = placeOrder(ada)
        val bolaOrder = placeOrder(bola)

        assertEquals(listOf(adaOrder.orderId), ids("customerId" to ada.customerId.toString()))
        assertEquals(listOf(bolaOrder.orderId), ids("customer" to bola.email.uppercase()))
        assertEquals(listOf(bolaOrder.orderId), ids("customer" to bola.email.substring(2, 8)))
        assertEquals(listOf(adaOrder.orderId), ids("customer" to "ada $lastName"))
        assertEquals(listOf(bolaOrder.orderId, adaOrder.orderId), ids("customer" to lastName))
        assertEquals(2, search("customer" to lastName)["totalElements"].asInt())
        assertEquals(emptyList(), ids("customer" to "$lastName-nobody"))
    }

    @Test
    fun `status, paymentStatus and dates narrow the list, and all filters combine`() {
        val ada = buyer("Ada")
        val unpaid = placeOrder(ada)
        val waitingForRefund = placeOrder(ada).also { setState(it.orderId, "CANCELLED", "PAID") }
        val refunded = placeOrder(ada).also { setState(it.orderId, "CANCELLED", "REFUNDED") }
        val mine = "customerId" to ada.customerId.toString()

        assertEquals(listOf(unpaid.orderId), ids(mine, "paymentStatus" to "unpaid"))
        assertEquals(listOf(waitingForRefund.orderId), ids(mine, "status" to "CANCELLED", "paymentStatus" to "PAID"))
        assertEquals(listOf(refunded.orderId, waitingForRefund.orderId), ids(mine, "status" to "CANCELLED"))

        val day = unpaid.createdAt.toLocalDate()
        assertEquals(3, ids(mine, "from" to day.toString(), "to" to day.toString()).size)
        assertEquals(emptyList(), ids(mine, "to" to day.minusDays(1).toString()))
        assertEquals(emptyList(), ids(mine, "from" to day.plusDays(1).toString()))
        assertEquals(
            listOf(waitingForRefund.orderId),
            ids(mine, "customer" to lastName, "reference" to waitingForRefund.reference, "status" to "CANCELLED", "from" to day.toString()),
        )
    }

    @Test
    fun `bad filter values are 400 and customers are refused`() {
        listOf(
            "status" to "LOST",
            "paymentStatus" to "MAYBE",
            "from" to "29-10-2026",
            "customerId" to "abc",
        ).forEach { (name, value) ->
            mockMvc.get("/dev/admin/orders") {
                header("Authorization", "Bearer $adminToken")
                param(name, value)
            }.andExpect { responseCode("106") }
        }
        mockMvc.get("/dev/admin/orders") {
            header("Authorization", "Bearer $adminToken")
            param("from", "2026-10-29")
            param("to", "2026-10-28")
        }.andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("from must not be after to") }
        }
        mockMvc.get("/dev/admin/orders") {
            header("Authorization", "Bearer $adminToken")
            param("paymentStatus", "MAYBE")
        }.andExpect { jsonPath("$.responseMessage") { value("paymentStatus must be one of UNPAID, PAID, REFUNDED") } }

        mockMvc.get("/dev/admin/orders") {
            header("Authorization", "Bearer ${newCustomerToken()}")
            param("customer", "a")
        }.andExpect { responseCode("107") }
    }
}
