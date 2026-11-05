package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.service.PaymentService
import com.rita.ecommerceplatform.service.PaymentSettlement
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifying a payment (checkpoint 1.4, Step 3). Paystack is the fake; each test uses a fresh customer. */
class PaymentVerifyTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private lateinit var token: String
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        token = newCustomerToken()
        categoryId = createCategory()
        createAddress()
    }

    private data class Placed(val order: JsonNode, val productIds: List<Long>) {
        val orderId get() = order["orderId"].asLong()
        val reference: String get() = order["reference"].asString()
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createCategory(): Int {
        val result = mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun createProduct(stock: Int): Long {
        val result = mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to unique("Phone"),
                "sku" to unique("sku"),
                "categoryId" to categoryId,
                "price" to 1000,
                "stockQuantity" to stock,
            )
        }.andExpect { responseCode("00") }.andReturn()
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
        }.andExpect { responseCode("00") }
    }

    /** One new product (stock 10) per quantity, all in the cart, then checkout. */
    private fun placeOrder(quantities: List<Int> = listOf(2)): Placed {
        val productIds = quantities.map { quantity ->
            createProduct(stock = 10).also { productId ->
                mockMvc.post("/api/customer/cart/items") {
                    header("Authorization", "Bearer $token")
                    contentType = MediaType.APPLICATION_JSON
                    content = json("productId" to productId, "quantity" to quantity)
                }.andExpect { responseCode("00") }
            }
        }
        val result = mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", UUID.randomUUID().toString())
        }.andExpect { responseCode("00") }.andReturn()
        return Placed(readJson(result), productIds)
    }

    /** Starts a payment for the order and returns its reference. */
    private fun initialize(placed: Placed): String {
        val result = mockMvc.post("/api/customer/payments/initialize") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("orderReference" to placed.reference)
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["paymentReference"].asString()
    }

    private fun verify(paymentReference: String, withToken: String? = token): ResultActionsDsl =
        mockMvc.get("/api/customer/payments/verify/$paymentReference") {
            withToken?.let { header("Authorization", "Bearer $it") }
        }

    private fun stock(productId: Long): Int =
        jdbcClient.sql("SELECT productstockquantity FROM public.ec_product WHERE productid = :id")
            .param("id", productId).query(Int::class.javaObjectType).single()

    // CITEXT columns are cast to text: read raw, the driver returns them as PGobject, not String.
    private fun orderState(orderId: Long): Map<String, Any?> =
        jdbcClient.sql(
            "SELECT orderstatus::text AS status, orderpaymentstatus::text AS paymentstatus, orderpaidat FROM public.ec_order WHERE orderid = :id"
        ).param("id", orderId).query().singleRow()

    private fun paymentState(reference: String): Map<String, Any?> =
        jdbcClient.sql(
            """
            SELECT paymentstatus::text AS status, paymentgatewayresponse, paymentprovidertransactionid,
                   paymentchannel::text AS channel, paymentproviderresponse::text AS raw, paymentpaidat, paymentverifiedat
            FROM public.ec_payment WHERE paymentreference = :reference
            """
        ).param("reference", reference).query().singleRow()

    /** (productId, quantity change, stock after) of the order's SALE movements, by product. */
    private fun saleMovements(orderId: Long): List<Triple<Long, Int, Int>> =
        jdbcClient.sql(
            """
            SELECT inventorymovementproductid, inventorymovementquantitychange, inventorymovementstockafter
            FROM public.ec_inventory_movement
            WHERE inventorymovementorderid = :id AND inventorymovementtype = 'SALE'
            ORDER BY inventorymovementproductid
            """
        ).param("id", orderId).query { rs, _ -> Triple(rs.getLong(1), rs.getInt(2), rs.getInt(3)) }.list()

    /** (from, to, note) of the order's status history, oldest first. */
    private fun history(orderId: Long): List<Triple<String?, String, String?>> =
        jdbcClient.sql(
            """
            SELECT orderstatushistoryfromstatus::text, orderstatushistorytostatus::text, orderstatushistorynote
            FROM public.ec_order_status_history WHERE orderstatushistoryorderid = :id
            ORDER BY orderstatushistorycreatedat, orderstatushistoryid
            """
        ).param("id", orderId).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.list()

    private fun assertUntouched(placed: Placed) {
        assertEquals("PENDING", orderState(placed.orderId)["status"])
        assertEquals("UNPAID", orderState(placed.orderId)["paymentstatus"])
        placed.productIds.forEach { assertEquals(10, stock(it)) }
        assertEquals(emptyList(), saleMovements(placed.orderId))
    }

    @Test
    fun `a successful payment confirms the order and takes the stock`() {
        val placed = placeOrder(listOf(2, 3))
        val reference = initialize(placed)
        paymentGateway.pay(reference)

        verify(reference).andExpect {
            responseCode("00")
            jsonPath("$.data.paymentReference") { value(reference) }
            jsonPath("$.data.orderReference") { value(placed.reference) }
            jsonPath("$.data.paymentStatus") { value("SUCCESS") }
            jsonPath("$.data.orderStatus") { value("CONFIRMED") }
            jsonPath("$.data.orderPaymentStatus") { value("PAID") }
            jsonPath("$.data.currency") { value("NGN") }
            jsonPath("$.data.paidAt") { exists() }
        }

        val order = orderState(placed.orderId)
        assertEquals("CONFIRMED", order["status"])
        assertEquals("PAID", order["paymentstatus"])
        assertTrue(order["orderpaidat"] != null)

        val payment = paymentState(reference)
        assertEquals("SUCCESS", payment["status"])
        assertEquals("fake-$reference", payment["paymentprovidertransactionid"])
        assertEquals("card", payment["channel"])
        assertTrue((payment["raw"] as String).contains(reference))
        assertTrue(payment["paymentverifiedat"] != null)

        assertEquals(listOf(8, 7), placed.productIds.map { stock(it) })
        assertEquals(
            listOf(Triple(placed.productIds[0], -2, 8), Triple(placed.productIds[1], -3, 7)),
            saleMovements(placed.orderId),
        )
        assertEquals(
            listOf(Triple(null, "PENDING", "Order placed"), Triple("PENDING", "CONFIRMED", "Payment verified")),
            history(placed.orderId),
        )
    }

    @Test
    fun `verifying again changes nothing and does not ask Paystack again`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)
        verify(reference).andExpect { responseCode("00"); jsonPath("$.responseMessage") { value("Successful") } }

        verify(reference).andExpect {
            responseCode("00")
            jsonPath("$.data.paymentStatus") { value("SUCCESS") }
            jsonPath("$.data.orderStatus") { value("CONFIRMED") }
        }

        assertEquals(1, paymentGateway.verifyCalls(reference))
        assertEquals(8, stock(placed.productIds[0]))
        assertEquals(1, saleMovements(placed.orderId).size)
        assertEquals(2, history(placed.orderId).size)
    }

    @Test
    fun `two verifies at the same moment take the stock once`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)

        val pool = Executors.newFixedThreadPool(2)
        val statuses = try {
            pool.invokeAll(List(2) { Callable { verify(reference).andReturn().responseCodeOf() } }).map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(listOf("00", "00"), statuses)
        assertEquals(8, stock(placed.productIds[0]))
        assertEquals(1, saleMovements(placed.orderId).size)
        assertEquals("CONFIRMED", orderState(placed.orderId)["status"])
    }

    @Test
    fun `a failed or abandoned payment leaves the order unpaid and stock unchanged`() {
        val failed = placeOrder()
        val failedReference = initialize(failed)
        paymentGateway.fail(failedReference)
        verify(failedReference).andExpect {
            responseCode("00"); jsonPath("$.responseMessage") { value("Successful") }
            jsonPath("$.data.paymentStatus") { value("FAILED") }
            jsonPath("$.data.orderStatus") { value("PENDING") }
            jsonPath("$.data.orderPaymentStatus") { value("UNPAID") }
        }
        assertUntouched(failed)

        val abandoned = placeOrder()
        val abandonedReference = initialize(abandoned)
        paymentGateway.abandon(abandonedReference)
        verify(abandonedReference).andExpect { jsonPath("$.data.paymentStatus") { value("ABANDONED") } }
        assertUntouched(abandoned)

        // The customer can try again: a new attempt starts, because the old one is no longer PENDING.
        val retry = initialize(failed)
        assertTrue(retry != failedReference)
    }

    @Test
    fun `a payment the customer has not finished stays pending`() {
        val placed = placeOrder()
        val reference = initialize(placed)

        verify(reference).andExpect {
            responseCode("00")
            jsonPath("$.data.paymentStatus") { value("PENDING") }
            jsonPath("$.data.orderStatus") { value("PENDING") }
            jsonPath("$.data.paidAt") { value(null) }
        }
        assertEquals("PENDING", paymentState(reference)["status"])
        assertUntouched(placed)
    }

    @Test
    fun `a success for the wrong amount or currency is rejected`() {
        val cheap = placeOrder()
        val cheapReference = initialize(cheap)
        paymentGateway.pay(cheapReference, amountKobo = 100)
        verify(cheapReference).andExpect {
            responseCode("00"); jsonPath("$.responseMessage") { value("Successful") }
            jsonPath("$.data.paymentStatus") { value("FAILED") }
            jsonPath("$.data.orderPaymentStatus") { value("UNPAID") }
        }
        assertEquals(PaymentSettlement.MISMATCH, paymentState(cheapReference)["paymentgatewayresponse"])
        assertUntouched(cheap)

        val dollars = placeOrder()
        val dollarsReference = initialize(dollars)
        paymentGateway.pay(dollarsReference, currency = "USD")
        verify(dollarsReference).andExpect { jsonPath("$.data.paymentStatus") { value("FAILED") } }
        assertUntouched(dollars)
    }

    @Test
    fun `when stock ran out after payment the order is cancelled for a refund and no stock is taken`() {
        val placed = placeOrder(listOf(2, 3))
        val reference = initialize(placed)
        // Another order took the second product's stock first (the race noted in 1.3).
        jdbcClient.sql("UPDATE public.ec_product SET productstockquantity = 1 WHERE productid = :id")
            .param("id", placed.productIds[1]).update()
        paymentGateway.pay(reference)

        verify(reference).andExpect {
            responseCode("00")
            jsonPath("$.data.paymentStatus") { value("SUCCESS") }
            jsonPath("$.data.orderStatus") { value("CANCELLED") }
            jsonPath("$.data.orderPaymentStatus") { value("PAID") }
        }

        assertEquals(listOf(10, 1), placed.productIds.map { stock(it) })
        assertEquals(emptyList(), saleMovements(placed.orderId))
        val last = history(placed.orderId).last()
        assertEquals("PENDING", last.first)
        assertEquals("CANCELLED", last.second)
        assertTrue(last.third!!.startsWith("Out of stock after payment, refund needed"))
    }

    @Test
    fun `a second successful attempt for a paid order is marked as a duplicate`() {
        val placed = placeOrder()
        val first = initialize(placed)
        // The first attempt looks crashed, so a second one starts; then the customer pays on both pages.
        jdbcClient.sql("UPDATE public.ec_payment SET paymentstatus = 'FAILED' WHERE paymentreference = :ref").param("ref", first).update()
        val second = initialize(placed)
        jdbcClient.sql("UPDATE public.ec_payment SET paymentstatus = 'PENDING' WHERE paymentreference = :ref").param("ref", first).update()
        paymentGateway.pay(first)
        paymentGateway.pay(second)

        verify(second).andExpect { jsonPath("$.data.paymentStatus") { value("SUCCESS") } }
        verify(first).andExpect {
            responseCode("00")
            jsonPath("$.data.paymentStatus") { value("FAILED") }
            jsonPath("$.data.orderStatus") { value("CONFIRMED") }
        }

        assertEquals("Duplicate payment: order already paid by $second, refund needed", paymentState(first)["paymentgatewayresponse"])
        assertEquals(8, stock(placed.productIds[0]))
        assertEquals(1, saleMovements(placed.orderId).size)
    }

    @Test
    fun `a reference Paystack does not know stays pending, and Paystack down is a 502`() {
        val placed = placeOrder()
        val unknown = PaymentService.newReference()
        jdbcClient.sql(
            "INSERT INTO public.ec_payment (paymentorderid, paymentreference, paymentprovider, paymentamount) VALUES (:orderId, :ref, 'PAYSTACK', :amount)"
        ).param("orderId", placed.orderId).param("ref", unknown).param("amount", placed.order["total"].decimalValue()).update()
        verify(unknown).andExpect {
            responseCode("00")
            jsonPath("$.data.paymentStatus") { value("PENDING") }
        }

        val other = placeOrder()
        val reference = initialize(other)
        paymentGateway.pay(reference)
        paymentGateway.down = true
        try {
            verify(reference).andExpect { responseCode("22") }
        } finally {
            paymentGateway.down = false
        }
        assertEquals("PENDING", paymentState(reference)["status"])
        assertUntouched(other)
    }

    @Test
    fun `only the customer who owns the payment can verify it`() {
        val placed = placeOrder()
        val reference = initialize(placed)
        paymentGateway.pay(reference)

        verify(reference, withToken = newCustomerToken()).andExpect {
            responseCode("25")
            jsonPath("$.responseMessage") { value("Payment not found") }
        }
        verify("PAY-DOES-NOT-EXIST").andExpect { responseCode("25") }
        verify(reference, withToken = null).andExpect { responseCode("115") }
        verify(reference, withToken = adminToken).andExpect { responseCode("107") }
        assertUntouched(placed)
    }

    @Test
    fun `Paystack's UTC time is stored in the platform's UTC+1 convention`() {
        assertEquals(
            LocalDateTime.of(2026, 10, 23, 13, 30),
            PaymentSettlement.toPlatformTime(OffsetDateTime.parse("2026-10-23T12:30:00Z")),
        )
    }
}
