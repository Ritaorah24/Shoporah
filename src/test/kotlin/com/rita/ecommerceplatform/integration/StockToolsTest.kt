package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/** The admin stock tools: low / out-of-stock filter, stock adjustments with a reason, stock history (checkpoint 2.2, Step 7a). */
class StockToolsTest : IntegrationTestBase() {

    private lateinit var adminToken: String

    // Each test lists its own category, so other tests' products never appear in the results.
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        categoryId = createCategory()
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createCategory(): Int {
        val result = mockMvc.post("/dev/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun createProduct(stock: Int, threshold: Int? = null, name: String = "Stock phone"): Long {
        val result = mockMvc.post("/dev/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to name,
                "sku" to unique("sku"),
                "categoryId" to categoryId,
                "price" to 1000,
                "stockQuantity" to stock,
                "lowStockThreshold" to threshold,
            )
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["productId"].asLong()
    }

    private fun adminList(vararg params: Pair<String, String>): JsonNode = readJson(
        mockMvc.get("/dev/admin/products") {
            header("Authorization", "Bearer $adminToken")
            param("categoryId", categoryId.toString())
            params.forEach { (name, value) -> param(name, value) }
        }.andExpect { responseCode("00") }.andReturn(),
    )

    private fun ids(vararg params: Pair<String, String>): Set<Long> =
        adminList(*params)["content"].toList().map { it["productId"].asLong() }.toSet()

    private fun adjust(productId: Long, change: Int?, reason: String? = "Delivery from supplier", withToken: String = adminToken): ResultActionsDsl =
        mockMvc.post("/dev/admin/products/$productId/stock-adjustments") {
            header("Authorization", "Bearer $withToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("change" to change, "reason" to reason)
        }

    private fun movements(productId: Long, vararg params: Pair<String, String>): JsonNode = readJson(
        mockMvc.get("/dev/admin/products/$productId/stock-movements") {
            header("Authorization", "Bearer $adminToken")
            params.forEach { (name, value) -> param(name, value) }
        }.andExpect { responseCode("00") }.andReturn(),
    )

    private fun stock(productId: Long): Int =
        jdbcClient.sql("SELECT productstockquantity FROM public.ec_product WHERE productid = :id")
            .param("id", productId).query(Int::class.javaObjectType).single()

    private fun movementCount(productId: Long): Int =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_inventory_movement WHERE inventorymovementproductid = :id")
            .param("id", productId).query(Int::class.javaObjectType).single()

    @Test
    fun `stock=OUT and stock=LOW list the products that need restocking, by the same rule as the public page`() {
        val out = createProduct(stock = 0, threshold = 5)
        val low = createProduct(stock = 3, threshold = 5)
        val atThreshold = createProduct(stock = 5, threshold = 5)
        createProduct(stock = 6, threshold = 5)
        // No threshold: never "low", however small the stock.
        createProduct(stock = 1)

        assertEquals(setOf(out), ids("stock" to "OUT"))
        assertEquals(setOf(low, atThreshold), ids("stock" to "low"))
        assertEquals(5, adminList()["totalElements"].asInt())

        val byId = adminList()["content"].toList().associateBy { it["productId"].asLong() }
        assertEquals("OUT_OF_STOCK", byId.getValue(out)["availability"].asString())
        assertEquals("LOW_STOCK", byId.getValue(low)["availability"].asString())
        assertEquals("LOW_STOCK", byId.getValue(atThreshold)["availability"].asString())
    }

    @Test
    fun `the stock filter combines with the other filters and rejects unknown values`() {
        val lowPhone = createProduct(stock = 2, threshold = 5, name = "Low phone")
        createProduct(stock = 2, threshold = 5, name = "Low cable")
        val inactive = createProduct(stock = 0)
        mockMvc.patch("/dev/admin/products/$inactive/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to "INACTIVE")
        }.andExpect { responseCode("00") }

        assertEquals(setOf(lowPhone), ids("stock" to "LOW", "search" to "phone"))
        assertEquals(setOf(inactive), ids("stock" to "OUT", "status" to "INACTIVE"))
        assertEquals(emptySet(), ids("stock" to "OUT", "status" to "ACTIVE"))
        assertEquals(1, adminList("stock" to "LOW", "search" to "phone")["totalElements"].asInt())

        mockMvc.get("/dev/admin/products") {
            header("Authorization", "Bearer $adminToken")
            param("stock", "EMPTY")
        }.andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("stock must be one of LOW, OUT") }
        }
    }

    @Test
    fun `adding and removing stock logs a movement with the admin and the reason`() {
        val productId = createProduct(stock = 10)

        adjust(productId, 20, reason = "  Delivery from supplier  ").andExpect {
            responseCode("00")
            jsonPath("$.data.stockQuantity") { value(30) }
        }
        adjust(productId, -3, reason = "Damaged in the warehouse").andExpect {
            responseCode("00")
            jsonPath("$.data.stockQuantity") { value(27) }
        }

        val content = movements(productId)["content"]
        assertEquals(3, content.size())
        assertEquals(listOf("ADJUSTMENT", -3, 27, "Damaged in the warehouse"), content[0].summary())
        assertEquals(listOf("RESTOCK", 20, 30, "Delivery from supplier"), content[1].summary())
        assertEquals(listOf("RESTOCK", 10, 10, "Initial stock"), content[2].summary())
        assertEquals(seedAdminId(), content[0]["adminId"].asInt())
    }

    private fun JsonNode.summary(): List<Any> =
        listOf(this["type"].asString(), this["quantityChange"].asInt(), this["stockAfter"].asInt(), this["note"].asString())

    @Test
    fun `stock never goes below 0 and a bad adjustment changes nothing`() {
        val productId = createProduct(stock = 5)

        adjust(productId, -6).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Only 5 in stock") }
        }
        adjust(productId, 0).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("change must not be 0") }
        }
        adjust(productId, null).andExpect { responseCode("106") }
        adjust(productId, 100_001).andExpect { responseCode("106") }
        adjust(productId, -100_001).andExpect { responseCode("106") }
        adjust(productId, 1, reason = null).andExpect { responseCode("106") }
        adjust(productId, 1, reason = "   ").andExpect { responseCode("106") }
        adjust(productId, 1, reason = "x".repeat(301)).andExpect { responseCode("106") }

        assertEquals(5, stock(productId))
        assertEquals(1, movementCount(productId))

        // Down to exactly 0 is fine.
        adjust(productId, -5, reason = "Stock count").andExpect {
            responseCode("00")
            jsonPath("$.data.stockQuantity") { value(0) }
            jsonPath("$.data.availability") { value("OUT_OF_STOCK") }
        }
    }

    @Test
    fun `adjustments at the same time are each applied, and never take stock below 0`() {
        val productId = createProduct(stock = 5)
        val pool = Executors.newFixedThreadPool(8)
        val statuses = try {
            // 8 removals of 1 from a stock of 5, plus nothing else: exactly 5 can succeed.
            pool.invokeAll(List(8) { Callable { adjust(productId, -1, reason = "Count").andReturn().responseCodeOf() } })
                .map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(5, statuses.count { it == "00" })
        assertEquals(3, statuses.count { it == "106" })
        assertEquals(0, stock(productId))
        // Initial stock + one movement per successful removal; each stockAfter is distinct (4, 3, 2, 1, 0).
        assertEquals(6, movementCount(productId))
        assertEquals(setOf(0, 1, 2, 3, 4), movements(productId)["content"].toList().filter { it["type"].asString() == "ADJUSTMENT" }
            .map { it["stockAfter"].asInt() }.toSet())
    }

    @Test
    fun `the stock history shows sales with their order reference, newest first, in pages`() {
        val productId = createProduct(stock = 10)
        mockMvc.patch("/dev/admin/products/$productId") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("stockQuantity" to 12)
        }.andExpect { responseCode("00") }
        val orderReference = buyTwo(productId)
        adjust(productId, -1, reason = "Lost in transit").andExpect { responseCode("00") }

        val all = movements(productId)
        assertEquals(4, all["totalElements"].asInt())
        val content = all["content"]
        assertEquals(listOf("ADJUSTMENT", "SALE", "RESTOCK", "RESTOCK"), content.toList().map { it["type"].asString() })
        assertEquals(listOf(-1, -2, 2, 10), content.toList().map { it["quantityChange"].asInt() })
        assertEquals(listOf(9, 10, 12, 10), content.toList().map { it["stockAfter"].asInt() })
        assertEquals(orderReference, content[1]["orderReference"].asString())
        assertEquals("Edited by admin", content[2]["note"].asString())

        val secondPage = movements(productId, "page" to "1", "size" to "3")
        assertEquals(1, secondPage["content"].size())
        assertEquals("Initial stock", secondPage["content"][0]["note"].asString())
    }

    // A customer buys 2 and pays (the fake Paystack says success): a SALE movement of -2.
    private fun buyTwo(productId: Long): String {
        val token = newCustomerToken()
        mockMvc.post("/dev/customer/addresses") {
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
        mockMvc.post("/dev/customer/cart/items") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to productId, "quantity" to 2)
        }.andExpect { responseCode("00") }
        val reference = readJson(
            mockMvc.post("/dev/customer/orders") {
                header("Authorization", "Bearer $token")
                header("Idempotency-Key", UUID.randomUUID().toString())
            }.andExpect { responseCode("00") }.andReturn(),
        )["reference"].asString()
        val paymentReference = readJson(
            mockMvc.post("/dev/customer/payments/initialize") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = json("orderReference" to reference)
            }.andExpect { responseCode("00") }.andReturn(),
        )["paymentReference"].asString()
        paymentGateway.pay(paymentReference)
        mockMvc.get("/dev/customer/payments/verify/$paymentReference") { header("Authorization", "Bearer $token") }
            .andExpect { responseCode("00") }
        return reference
    }

    @Test
    fun `deleted or unknown products are 404 and customers are refused`() {
        val productId = createProduct(stock = 5)
        mockMvc.delete("/dev/admin/products/$productId") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("00") }

        adjust(productId, 1).andExpect { responseCode("25") }
        mockMvc.get("/dev/admin/products/$productId/stock-movements") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("25") }
        adjust(999_999_999, 1).andExpect { responseCode("25") }
        assertEquals(5, stock(productId))

        val customerToken = newCustomerToken()
        val other = createProduct(stock = 5)
        adjust(other, 1, withToken = customerToken).andExpect { responseCode("107") }
        mockMvc.get("/dev/admin/products/$other/stock-movements") { header("Authorization", "Bearer $customerToken") }
            .andExpect { responseCode("107") }
        mockMvc.get("/dev/admin/products") {
            header("Authorization", "Bearer $customerToken")
            param("stock", "LOW")
        }.andExpect { responseCode("107") }
    }
}
