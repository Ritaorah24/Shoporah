package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.service.RefundService
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Admin refunds through the payment provider, settled by refresh (checkpoint 2.2, Step 5a). Paystack is the fake. */
class RefundTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private lateinit var email: String
    private lateinit var token: String
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        email = uniqueEmail()
        registerCustomer(email)
        token = loginCustomer(email)
        categoryId = createCategory()
        createAddress()
    }

    private data class Paid(val orderId: Long, val reference: String, val paymentReference: String, val productIds: List<Long>)

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

    private fun createAddress() {
        mockMvc.post("/api/customer/addresses") {
            header("Authorization", "Bearer $token")
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

    /** 2 of each of two products (stock 10 → 8 once paid): total ₦4,000.00. Returns the order and its payment reference. */
    private fun placeOrder(): Triple<Long, String, List<Long>> {
        val productIds = listOf(createProduct(), createProduct())
        productIds.forEach { productId ->
            mockMvc.post("/api/customer/cart/items") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = json("productId" to productId, "quantity" to 2)
            }.andExpect { status { isOk() } }
        }
        val order = readJson(
            mockMvc.post("/api/customer/orders") {
                header("Authorization", "Bearer $token")
                header("Idempotency-Key", UUID.randomUUID().toString())
            }.andExpect { status { isCreated() } }.andReturn(),
        )
        return Triple(order["orderId"].asLong(), order["reference"].asString(), productIds)
    }

    private fun initialize(orderReference: String): String {
        val result = mockMvc.post("/api/customer/payments/initialize") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("orderReference" to orderReference)
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["paymentReference"].asString()
    }

    private fun paidOrder(): Paid {
        val (orderId, reference, productIds) = placeOrder()
        val paymentReference = initialize(reference)
        paymentGateway.pay(paymentReference)
        mockMvc.get("/api/customer/payments/verify/$paymentReference") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }
        return Paid(orderId, reference, paymentReference, productIds)
    }

    private fun cancelledPaidOrder(): Paid = paidOrder().also {
        mockMvc.post("/api/admin/orders/${it.orderId}/cancel") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("reason" to "Item damaged")
        }.andExpect { status { isOk() } }
    }

    private fun deliveredOrder(): Paid = paidOrder().also { paid ->
        listOf("PROCESSING", "SHIPPED", "DELIVERED").forEach { moveTo(paid.orderId, it) }
    }

    private fun moveTo(orderId: Long, status: String) {
        mockMvc.patch("/api/admin/orders/$orderId/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to status)
        }.andExpect { status { isOk() } }
    }

    private fun refund(
        orderId: Long,
        reason: String? = "Customer returned it",
        restock: Boolean? = null,
        withToken: String? = adminToken,
    ): ResultActionsDsl =
        mockMvc.post("/api/admin/orders/$orderId/refund") {
            withToken?.let { header("Authorization", "Bearer $it") }
            contentType = MediaType.APPLICATION_JSON
            content = if (restock == null) json("reason" to reason) else json("reason" to reason, "restock" to restock)
        }

    /** Starts a refund that must be accepted and returns its refundId. */
    private fun startRefund(orderId: Long, restock: Boolean? = null): Long {
        val order = readJson(refund(orderId, restock = restock).andExpect { status { isAccepted() } }.andReturn())
        return order["refunds"].last()["refundId"].asLong()
    }

    private fun refresh(refundId: Long): ResultActionsDsl =
        mockMvc.post("/api/admin/refunds/$refundId/refresh") { header("Authorization", "Bearer $adminToken") }

    private fun adminOrder(orderId: Long): JsonNode = readJson(
        mockMvc.get("/api/admin/orders/$orderId") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isOk() } }.andReturn(),
    )

    private fun JsonNode.last(): JsonNode = get(size() - 1)

    private fun stock(productId: Long): Int =
        jdbcClient.sql("SELECT productstockquantity FROM public.ec_product WHERE productid = :id")
            .param("id", productId).query(Int::class.javaObjectType).single()

    /** (productId, quantity change, stock after) of the order's REFUND_RETURN movements. */
    private fun returns(orderId: Long): List<List<Any>> =
        jdbcClient.sql(
            """
            SELECT inventorymovementproductid, inventorymovementquantitychange, inventorymovementstockafter
            FROM public.ec_inventory_movement
            WHERE inventorymovementorderid = :id AND inventorymovementtype = 'REFUND_RETURN'
            ORDER BY inventorymovementproductid
            """
        ).param("id", orderId).query { rs, _ -> listOf<Any>(rs.getLong(1), rs.getInt(2), rs.getInt(3)) }.list()

    private fun refundEmails(reference: String): List<String> {
        drainOutbox()
        return emailChannel.sentTo(email).filter { it.subject == "Your refund for order $reference" }.map { it.body!! }
    }

    private fun refundRows(orderId: Long): Int =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_refund WHERE refundorderid = :id")
            .param("id", orderId).query(Int::class.javaObjectType).single()

    @Test
    fun `a cancelled paid order is refunded once Paystack processes it`() {
        val paid = cancelledPaidOrder()

        refund(paid.orderId, reason = "  Item damaged in the warehouse  ").andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("CANCELLED") }
            jsonPath("$.paymentStatus") { value("PAID") }
            jsonPath("$.canRefund") { value(false) }
            jsonPath("$.refunds.length()") { value(1) }
            jsonPath("$.refunds[0].status") { value("PENDING") }
            jsonPath("$.refunds[0].amount") { value(4000.0) }
            jsonPath("$.refunds[0].reason") { value("Item damaged in the warehouse") }
            jsonPath("$.refunds[0].providerStatus") { value("pending") }
            jsonPath("$.refunds[0].providerRefundId") { isNotEmpty() }
        }
        val request = paymentGateway.refundRequests(paid.paymentReference).single()
        assertEquals(400_000, request.amountKobo)
        assertEquals("NGN", request.currency)
        assertEquals("Item damaged in the warehouse", request.merchantNote)
        assertTrue(refundEmails(paid.reference).isEmpty())

        val refundId = adminOrder(paid.orderId)["refunds"][0]["refundId"].asLong()
        paymentGateway.processRefund(paid.paymentReference)
        refresh(refundId).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CANCELLED") }
            jsonPath("$.paymentStatus") { value("REFUNDED") }
            jsonPath("$.canRefund") { value(false) }
            jsonPath("$.refunds[0].status") { value("PROCESSED") }
            jsonPath("$.refunds[0].processedAt") { isNotEmpty() }
            jsonPath("$.payments[0].status") { value("SUCCESS") }
            jsonPath("$.statusHistory[3].status") { value("CANCELLED") }
            jsonPath("$.statusHistory[3].changedBy") { value("ADMIN") }
            jsonPath("$.statusHistory[3].note") { value("Refund processed: ₦4,000.00") }
        }
        // Stock went back when it was cancelled; the refund does not add it again.
        paid.productIds.forEach { assertEquals(10, stock(it)) }
        assertTrue(returns(paid.orderId).isEmpty())

        mockMvc.get("/api/customer/orders/${paid.orderId}") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.paymentStatus") { value("REFUNDED") }
        }
        val body = refundEmails(paid.reference).single()
        assertTrue("Your refund of ₦4,000.00 for order ${paid.reference} has been processed." in body, body)
    }

    @Test
    fun `a returned delivered order is refunded and its stock goes back exactly once`() {
        val paid = deliveredOrder()
        paid.productIds.forEach { assertEquals(8, stock(it)) }
        val refundId = startRefund(paid.orderId, restock = true)
        // Nothing changes until Paystack has processed it.
        paid.productIds.forEach { assertEquals(8, stock(it)) }

        paymentGateway.processRefund(paid.paymentReference)
        refresh(refundId).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REFUNDED") }
            jsonPath("$.paymentStatus") { value("REFUNDED") }
            jsonPath("$.nextStatuses.length()") { value(0) }
            jsonPath("$.refunds[0].restock") { value(true) }
            jsonPath("$.statusHistory[5].fromStatus") { value("DELIVERED") }
            jsonPath("$.statusHistory[5].status") { value("REFUNDED") }
            jsonPath("$.statusHistory[5].note") { value("Refund processed: ₦4,000.00. Items returned to stock") }
        }
        paid.productIds.forEach { assertEquals(10, stock(it)) }
        assertEquals(paid.productIds.sorted().map { listOf<Any>(it, 2, 10) }, returns(paid.orderId))

        // A second refresh of a settled refund changes nothing.
        refresh(refundId).andExpect { status { isOk() } }
        paid.productIds.forEach { assertEquals(10, stock(it)) }
        assertEquals(2, returns(paid.orderId).size)
        assertEquals(1, refundEmails(paid.reference).size)
    }

    @Test
    fun `a delivered order refunded without restock keeps its stock as sold`() {
        val paid = deliveredOrder()
        val refundId = startRefund(paid.orderId)
        paymentGateway.processRefund(paid.paymentReference)

        refresh(refundId).andExpect {
            jsonPath("$.status") { value("REFUNDED") }
            jsonPath("$.refunds[0].restock") { value(false) }
            jsonPath("$.statusHistory[5].note") { value("Refund processed: ₦4,000.00") }
        }
        paid.productIds.forEach { assertEquals(8, stock(it)) }
        assertTrue(returns(paid.orderId).isEmpty())
    }

    @Test
    fun `restock is refused for a cancelled order, whose stock already went back`() {
        val paid = cancelledPaidOrder()

        refund(paid.orderId, restock = true).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("restock is only for delivered orders: a cancelled order's stock was already put back") }
        }
        assertEquals(0, refundRows(paid.orderId))
        assertTrue(paymentGateway.refundRequests(paid.paymentReference).isEmpty())
    }

    @Test
    fun `a refund that fails at Paystack leaves the order paid, and it can be tried again`() {
        val paid = cancelledPaidOrder()
        val refundId = startRefund(paid.orderId)

        paymentGateway.failRefund(paid.paymentReference, reason = "Customer bank account closed")
        refresh(refundId).andExpect {
            status { isOk() }
            jsonPath("$.paymentStatus") { value("PAID") }
            jsonPath("$.canRefund") { value(true) }
            jsonPath("$.refunds[0].status") { value("FAILED") }
            jsonPath("$.refunds[0].providerStatus") { value("failed") }
            jsonPath("$.refunds[0].failureReason") { value("Customer bank account closed") }
        }

        refund(paid.orderId).andExpect {
            status { isAccepted() }
            jsonPath("$.refunds.length()") { value(2) }
            jsonPath("$.refunds[1].status") { value("PENDING") }
        }
        assertEquals(2, paymentGateway.refundRequests(paid.paymentReference).size)
        assertTrue(refundEmails(paid.reference).isEmpty())
    }

    @Test
    fun `a refund Paystack refuses at the start fails at once with its message`() {
        val paid = cancelledPaidOrder()
        paymentGateway.rejectRefund(paid.paymentReference, "Insufficient balance to process refund")

        refund(paid.orderId).andExpect {
            status { isAccepted() }
            jsonPath("$.paymentStatus") { value("PAID") }
            jsonPath("$.canRefund") { value(true) }
            jsonPath("$.refunds[0].status") { value("FAILED") }
            jsonPath("$.refunds[0].failureReason") { value("Insufficient balance to process refund") }
            jsonPath("$.refunds[0].providerRefundId") { doesNotExist() }
        }
        refund(paid.orderId).andExpect {
            status { isAccepted() }
            jsonPath("$.refunds[1].status") { value("PENDING") }
        }
    }

    @Test
    fun `a refund whose answer was lost is found at Paystack on refresh and never sent twice`() {
        val paid = cancelledPaidOrder()
        paymentGateway.loseRefundAnswer(paid.paymentReference)

        refund(paid.orderId).andExpect {
            status { isAccepted() }
            jsonPath("$.canRefund") { value(false) }
            jsonPath("$.refunds[0].status") { value("PENDING") }
            jsonPath("$.refunds[0].providerRefundId") { doesNotExist() }
        }
        val refundId = adminOrder(paid.orderId)["refunds"][0]["refundId"].asLong()

        // Paystack has it and still works on it (needs-attention): it is linked, still PENDING.
        paymentGateway.refundNeedsAttention(paid.paymentReference)
        refresh(refundId).andExpect {
            status { isOk() }
            jsonPath("$.refunds[0].status") { value("PENDING") }
            jsonPath("$.refunds[0].providerStatus") { value("needs-attention") }
            jsonPath("$.refunds[0].providerRefundId") { isNotEmpty() }
        }
        paymentGateway.processRefund(paid.paymentReference)
        refresh(refundId).andExpect { jsonPath("$.paymentStatus") { value("REFUNDED") } }

        assertEquals(1, paymentGateway.refundRequests(paid.paymentReference).size)
        assertEquals(1, refundEmails(paid.reference).size)
    }

    @Test
    fun `a refund Paystack never received is marked failed after the grace period`() {
        val paid = cancelledPaidOrder()
        paymentGateway.down = true
        try {
            refund(paid.orderId).andExpect {
                status { isAccepted() }
                jsonPath("$.refunds[0].status") { value("PENDING") }
            }
        } finally {
            paymentGateway.down = false
        }
        val refundId = adminOrder(paid.orderId)["refunds"][0]["refundId"].asLong()

        // Too soon to be sure: it may still be on its way to Paystack.
        refresh(refundId).andExpect { jsonPath("$.refunds[0].status") { value("PENDING") } }

        jdbcClient.sql("UPDATE public.ec_refund SET refundcreatedat = refundcreatedat - INTERVAL '2 minutes' WHERE refundid = :id")
            .param("id", refundId).update()
        refresh(refundId).andExpect {
            jsonPath("$.paymentStatus") { value("PAID") }
            jsonPath("$.canRefund") { value(true) }
            jsonPath("$.refunds[0].status") { value("FAILED") }
            jsonPath("$.refunds[0].failureReason") { value(RefundService.NOT_RECEIVED) }
        }
        refund(paid.orderId).andExpect { status { isAccepted() } }
    }

    @Test
    fun `a second refund while one is in progress is refused, also when both arrive at once`() {
        val first = cancelledPaidOrder()
        startRefund(first.orderId)
        refund(first.orderId).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("A refund for this order is already in progress") }
        }

        val second = cancelledPaidOrder()
        val pool = Executors.newFixedThreadPool(2)
        val statuses = try {
            pool.invokeAll(List(2) { Callable { refund(second.orderId).andReturn().response.status } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(listOf(202, 409), statuses.sorted())
        assertEquals(1, refundRows(second.orderId))
        assertEquals(1, paymentGateway.refundRequests(second.paymentReference).size)
    }

    @Test
    fun `the database rejects a second active refund for the same payment`() {
        val paid = cancelledPaidOrder()
        startRefund(paid.orderId)

        assertFailsWith<DuplicateKeyException> {
            jdbcClient.sql(
                """
                INSERT INTO public.ec_refund (refundorderid, refundpaymentid, refundamount, refundreason, refundrequestedbyadminid)
                SELECT refundorderid, refundpaymentid, refundamount, 'again', refundrequestedbyadminid
                FROM public.ec_refund WHERE refundorderid = :orderId
                """
            ).param("orderId", paid.orderId).update()
        }
    }

    @Test
    fun `orders that are unpaid, not yet cancelled or delivered, or already refunded cannot be refunded`() {
        val (unpaidId, _, _) = placeOrder()
        refund(unpaidId).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("Only paid orders can be refunded") }
        }
        assertEquals(false, adminOrder(unpaidId)["canRefund"].asBoolean())

        val confirmed = paidOrder()
        refund(confirmed.orderId).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("Only cancelled or delivered orders can be refunded; cancel the order first if it has not shipped") }
        }
        assertEquals(false, adminOrder(confirmed.orderId)["canRefund"].asBoolean())
        listOf("PROCESSING", "SHIPPED").forEach { moveTo(confirmed.orderId, it) }
        refund(confirmed.orderId).andExpect { status { isConflict() } }
        moveTo(confirmed.orderId, "DELIVERED")
        assertEquals(true, adminOrder(confirmed.orderId)["canRefund"].asBoolean())

        val refundId = startRefund(confirmed.orderId)
        paymentGateway.processRefund(confirmed.paymentReference)
        refresh(refundId)
        refund(confirmed.orderId).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("This order has already been refunded") }
        }
        assertEquals(1, paymentGateway.refundRequests(confirmed.paymentReference).size)
    }

    @Test
    fun `refunds need a reason, an admin and an existing order or refund`() {
        val paid = cancelledPaidOrder()
        refund(paid.orderId, reason = null).andExpect { status { isBadRequest() } }
        refund(paid.orderId, reason = "   ").andExpect { status { isBadRequest() } }
        refund(paid.orderId, reason = "x".repeat(501)).andExpect { status { isBadRequest() } }
        refund(paid.orderId, withToken = token).andExpect { status { isForbidden() } }
        refund(paid.orderId, withToken = null).andExpect { status { isUnauthorized() } }
        refund(999_999_999).andExpect { status { isNotFound() } }
        refresh(999_999_999).andExpect { status { isNotFound() } }
        mockMvc.post("/api/admin/refunds/1/refresh") { header("Authorization", "Bearer $token") }
            .andExpect { status { isForbidden() } }
        assertEquals(0, refundRows(paid.orderId))

        // The order view lists no refunds yet and allows one.
        val order = adminOrder(paid.orderId)
        assertEquals(0, order["refunds"].size())
        assertEquals(true, order["canRefund"].asBoolean())
    }
}
