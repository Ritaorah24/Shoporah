package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.service.OrderCancellation
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

/** A customer cancelling their own unpaid order (checkpoint 2.2, Step 3). Paystack is the fake. */
class CustomerCancelTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private lateinit var email: String
    private lateinit var token: String
    private var customerId: Long = 0
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        email = uniqueEmail()
        customerId = registerCustomer(email)
        token = loginCustomer(email)
        categoryId = createCategory()
        createAddress(token)
    }

    private data class Placed(val orderId: Long, val reference: String, val productId: Long)

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createCategory(): Int {
        val result = mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun createProduct(): Long {
        val result = mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to unique("Phone"),
                "sku" to unique("sku"),
                "categoryId" to categoryId,
                "price" to 1000,
                "stockQuantity" to 10,
            )
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["productId"].asLong()
    }

    private fun createAddress(customerToken: String) {
        mockMvc.post("/api/customer/addresses") {
            header("Authorization", "Bearer $customerToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "recipientName" to "Ada Obi",
                "recipientPhoneNumber" to "08012345678",
                "addressLine1" to "12 Allen Avenue",
                "city" to "Ikeja",
                "state" to "Lagos",
                "country" to "Nigeria",
            )
        }.andExpect { status { isCreated() } }
    }

    /** One new product (stock 10), 2 of it in the cart, then checkout. */
    private fun placeOrder(customerToken: String = token): Placed {
        val productId = createProduct()
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer $customerToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to productId, "quantity" to 2)
        }.andExpect { status { isOk() } }
        val result = mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer $customerToken")
            header("Idempotency-Key", UUID.randomUUID().toString())
        }.andExpect { status { isCreated() } }.andReturn()
        val order = readJson(result)
        return Placed(order["orderId"].asLong(), order["reference"].asString(), productId)
    }

    private fun initialize(placed: Placed): String {
        val result = mockMvc.post("/api/customer/payments/initialize") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("orderReference" to placed.reference)
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["paymentReference"].asString()
    }

    private fun cancel(orderId: Long, reason: String? = null, withToken: String? = token): ResultActionsDsl =
        mockMvc.post("/api/customer/orders/$orderId/cancel") {
            withToken?.let { header("Authorization", "Bearer $it") }
            if (reason != null) {
                contentType = MediaType.APPLICATION_JSON
                content = json("reason" to reason)
            }
        }

    private fun orderStatus(orderId: Long): String =
        jdbcClient.sql("SELECT orderstatus::text FROM public.ec_order WHERE orderid = :id")
            .param("id", orderId).query(String::class.java).single()

    private fun paymentState(reference: String): Map<String, Any?> =
        jdbcClient.sql("SELECT paymentstatus::text AS status, paymentgatewayresponse FROM public.ec_payment WHERE paymentreference = :reference")
            .param("reference", reference).query().singleRow()

    private fun stock(productId: Long): Int =
        jdbcClient.sql("SELECT productstockquantity FROM public.ec_product WHERE productid = :id")
            .param("id", productId).query(Int::class.javaObjectType).single()

    private fun cancelledSubjects(): List<String?> {
        drainOutbox()
        return emailChannel.sentTo(email).map { it.subject }.filter { it!!.endsWith("was cancelled") }
    }

    @Test
    fun `an unpaid order with no payment is cancelled, with the reason and one email`() {
        val placed = placeOrder()

        cancel(placed.orderId, reason = "  Ordered the wrong size  ").andExpect {
            status { isOk() }
            jsonPath("$.orderId") { value(placed.orderId) }
            jsonPath("$.status") { value("CANCELLED") }
            jsonPath("$.paymentStatus") { value("UNPAID") }
            jsonPath("$.statusHistory.length()") { value(2) }
            jsonPath("$.statusHistory[1].status") { value("CANCELLED") }
            jsonPath("$.statusHistory[1].note") { value("${OrderCancellation.CANCELLED_BY_CUSTOMER}: Ordered the wrong size") }
        }

        val changedBy = jdbcClient.sql(
            """
            SELECT orderstatushistorychangedbycustomerid FROM public.ec_order_status_history
            WHERE orderstatushistoryorderid = :id AND orderstatushistorytostatus = 'CANCELLED'
            """
        ).param("id", placed.orderId).query(Long::class.javaObjectType).single()
        assertEquals(customerId, changedBy)
        // Nothing was taken on checkout, so nothing changes on cancel.
        assertEquals(10, stock(placed.productId))
        assertEquals(listOf("Your order ${placed.reference} was cancelled"), cancelledSubjects())
    }

    @Test
    fun `an open payment that was not paid is closed with the order`() {
        val placed = placeOrder()
        val reference = initialize(placed)

        cancel(placed.orderId).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CANCELLED") }
            jsonPath("$.statusHistory[1].note") { value(OrderCancellation.CANCELLED_BY_CUSTOMER) }
        }

        val payment = paymentState(reference)
        assertEquals("FAILED", payment["status"])
        assertEquals(OrderCancellation.PAYMENT_CLOSED, payment["paymentgatewayresponse"])
        // Paystack was asked before closing it.
        assertEquals(1, paymentGateway.verifyCalls(reference))
    }

    @Test
    fun `a payment made just before the cancel confirms the order instead`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)

        cancel(placed.orderId).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("This order has already been paid and can no longer be cancelled") }
        }

        assertEquals("CONFIRMED", orderStatus(placed.orderId))
        assertEquals("SUCCESS", paymentState(reference)["status"])
        assertEquals(8, stock(placed.productId))
        assertEquals(emptyList(), cancelledSubjects())
        assertEquals(1, emailChannel.sentTo(email).count { it.subject == "Payment received for order ${placed.reference}" })
    }

    @Test
    fun `when Paystack cannot be reached nothing changes`() {
        val placed = placeOrder()
        val reference = initialize(placed)

        paymentGateway.down = true
        try {
            cancel(placed.orderId).andExpect { status { isBadGateway() } }
        } finally {
            paymentGateway.down = false
        }

        assertEquals("PENDING", orderStatus(placed.orderId))
        assertEquals("PENDING", paymentState(reference)["status"])
        assertEquals(emptyList(), cancelledSubjects())
    }

    @Test
    fun `a paid order cannot be cancelled by the customer`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)
        mockMvc.get("/api/customer/payments/verify/$reference") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }

        cancel(placed.orderId).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("Only unpaid pending orders can be cancelled") }
        }
        assertEquals("CONFIRMED", orderStatus(placed.orderId))
        assertEquals(8, stock(placed.productId))
    }

    @Test
    fun `cancelling twice is refused the second time and sends one email`() {
        val placed = placeOrder()

        cancel(placed.orderId).andExpect { status { isOk() } }
        cancel(placed.orderId).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("Only unpaid pending orders can be cancelled") }
        }
        assertEquals(1, cancelledSubjects().size)
    }

    @Test
    fun `another customer's order is not found`() {
        val placed = placeOrder()
        val otherToken = newCustomerToken()

        cancel(placed.orderId, withToken = otherToken).andExpect {
            status { isNotFound() }
            jsonPath("$.message") { value("Order not found") }
        }
        cancel(999_999_999).andExpect { status { isNotFound() } }
        assertEquals("PENDING", orderStatus(placed.orderId))
    }

    @Test
    fun `a cancelled order cannot be paid`() {
        val placed = placeOrder()
        cancel(placed.orderId).andExpect { status { isOk() } }

        mockMvc.post("/api/customer/payments/initialize") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("orderReference" to placed.reference)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("This order cannot be paid") }
        }
    }

    @Test
    fun `a too long reason, no token and an admin token are rejected`() {
        val placed = placeOrder()

        cancel(placed.orderId, reason = "x".repeat(501)).andExpect { status { isBadRequest() } }
        cancel(placed.orderId, withToken = null).andExpect { status { isUnauthorized() } }
        cancel(placed.orderId, withToken = adminToken).andExpect { status { isForbidden() } }
        assertEquals("PENDING", orderStatus(placed.orderId))
    }
}
