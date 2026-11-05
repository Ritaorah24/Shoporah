package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/** Coupons at checkout (checkpoint 3.1, Step 2): the discount, clear refusals, safe counting, and giving uses back. */
class CouponCheckoutTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private var categoryId: Int = 0

    // A delivery method with a known fee, so totals do not depend on STANDARD's fee.
    private lateinit var deliveryCode: String

    private data class Shopper(val email: String, val token: String)

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        categoryId = readJson(
            mockMvc.post("/api/admin/categories") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = json("name" to unique("Cat"))
            }.andExpect { responseCode("00") }.andReturn()
        )["categoryId"].asInt()
        deliveryCode = createDeliveryMethod(1500)
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun uniqueCode() = "CC_${UUID.randomUUID().toString().take(8).uppercase()}"

    private fun platformNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.ofHours(1))

    private fun createDeliveryMethod(fee: Int): String {
        val code = "DM_${UUID.randomUUID().toString().take(8).uppercase()}"
        mockMvc.post("/api/admin/delivery-methods") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("code" to code, "name" to "Test delivery", "fee" to fee)
        }.andExpect { responseCode("00") }
        return code
    }

    /** Creates a coupon and returns its id. */
    private fun createCoupon(code: String, vararg fields: Pair<String, Any?>): Int =
        readJson(
            mockMvc.post("/api/admin/coupons") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = json("code" to code, *fields)
            }.andExpect { responseCode("00") }.andReturn()
        )["couponId"].asInt()

    private fun percentCoupon(code: String, percent: Int, vararg extra: Pair<String, Any?>): Int =
        createCoupon(code, "discountType" to "PERCENTAGE", "discountValue" to percent, *extra)

    private fun fixedCoupon(code: String, amount: Int, vararg extra: Pair<String, Any?>): Int =
        createCoupon(code, "discountType" to "FIXED_AMOUNT", "discountValue" to amount, *extra)

    private fun usageCount(couponId: Int): Int =
        jdbcClient.sql("SELECT couponusagecount FROM public.ec_coupon WHERE couponid = :id")
            .param("id", couponId).query(Int::class.javaObjectType).single()

    private fun setCouponColumn(couponId: Int, column: String, value: Any) {
        jdbcClient.sql("UPDATE public.ec_coupon SET $column = :value WHERE couponid = :id")
            .param("value", value).param("id", couponId).update()
    }

    private fun newShopper(): Shopper {
        val email = uniqueEmail()
        registerCustomer(email)
        val shopper = Shopper(email, loginCustomer(email))
        mockMvc.post("/api/customer/addresses") {
            header("Authorization", "Bearer ${shopper.token}")
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
        return shopper
    }

    /** Puts `quantity` units of a new product priced `price` in the cart. */
    private fun fillCart(shopper: Shopper, price: Any = 10000, quantity: Int = 2) {
        val product = mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Phone"), "sku" to unique("sku"), "categoryId" to categoryId, "price" to price, "stockQuantity" to 50)
        }.andExpect { responseCode("00") }.andReturn()
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer ${shopper.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to readJson(product)["productId"].asLong(), "quantity" to quantity)
        }.andExpect { responseCode("00") }
    }

    private fun checkout(
        shopper: Shopper,
        couponCode: String?,
        key: UUID = UUID.randomUUID(),
        delivery: String = deliveryCode,
    ): ResultActionsDsl =
        mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer ${shopper.token}")
            header("Idempotency-Key", key.toString())
            contentType = MediaType.APPLICATION_JSON
            content = json("deliveryMethodCode" to delivery, "couponCode" to couponCode)
        }

    private fun placeOrder(shopper: Shopper, couponCode: String?): JsonNode =
        readJson(checkout(shopper, couponCode).andExpect { responseCode("00"); jsonPath("$.responseMessage") { value("Order created") } }.andReturn())

    private fun preview(shopper: Shopper, code: String): ResultActionsDsl =
        mockMvc.post("/api/customer/coupons/validate") {
            header("Authorization", "Bearer ${shopper.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json("code" to code)
        }

    private fun cartSize(shopper: Shopper): Int =
        readJson(mockMvc.get("/api/customer/cart") { header("Authorization", "Bearer ${shopper.token}") }.andReturn())["items"].size()

    private fun money(node: JsonNode): BigDecimal = node.decimalValue()

    private fun assertMoney(expected: String, actual: JsonNode) = assertEquals(0, BigDecimal(expected).compareTo(money(actual)), "expected $expected, was $actual")

    private fun customerCancel(shopper: Shopper, orderId: Long) {
        mockMvc.post("/api/customer/orders/$orderId/cancel") { header("Authorization", "Bearer ${shopper.token}") }
            .andExpect { responseCode("00") }
    }

    private fun adminCancel(orderId: Long) {
        mockMvc.post("/api/admin/orders/$orderId/cancel") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("reason" to "Out of stock at the warehouse")
        }.andExpect { responseCode("00") }
    }

    private fun pay(shopper: Shopper, order: JsonNode): Long {
        val payment = readJson(
            mockMvc.post("/api/customer/payments/initialize") {
                header("Authorization", "Bearer ${shopper.token}")
                contentType = MediaType.APPLICATION_JSON
                content = json("orderReference" to order["reference"].asString())
            }.andExpect { responseCode("00") }.andReturn()
        )
        val reference = payment["paymentReference"].asString()
        paymentGateway.pay(reference)
        mockMvc.get("/api/customer/payments/verify/$reference") { header("Authorization", "Bearer ${shopper.token}") }
            .andExpect { responseCode("00") }
        return paymentGateway.initialized.single { it.reference == reference }.amountKobo
    }

    @Test
    fun `a percentage coupon is capped at its maximum, Paystack is asked for the lower total, and the order shows the code`() {
        val code = uniqueCode()
        val couponId = percentCoupon(code, 10, "maxDiscountAmount" to 1500)
        val shopper = newShopper()
        fillCart(shopper) // 2 x 10,000 = 20,000; 10% = 2,000, capped at 1,500

        val order = placeOrder(shopper, code.lowercase())
        assertMoney("20000", order["subtotal"])
        assertMoney("1500", order["discountAmount"])
        assertMoney("1500", order["deliveryFee"])
        assertMoney("20000", order["total"])
        assertEquals(code, order["couponCode"].asString())
        assertEquals(1, usageCount(couponId))

        assertEquals(2_000_000L, pay(shopper, order))
        mockMvc.get("/api/admin/orders/${order["orderId"].asLong()}") { header("Authorization", "Bearer $adminToken") }.andExpect {
            jsonPath("$.data.couponCode") { value(code) }
            jsonPath("$.data.discountAmount") { value(1500.0) }
        }
        mockMvc.get("/api/customer/orders/${order["orderId"].asLong()}") { header("Authorization", "Bearer ${shopper.token}") }
            .andExpect { jsonPath("$.data.couponCode") { value(code) } }

        // Without a coupon there is no code and no discount.
        val plain = newShopper().also { fillCart(it) }
        val noCoupon = placeOrder(plain, null)
        assertEquals(true, noCoupon["couponCode"].isNull)
        assertMoney("0", noCoupon["discountAmount"])
    }

    @Test
    fun `percentages round to the kobo, and a fixed amount never goes above the subtotal or touches the delivery fee`() {
        val percent = uniqueCode()
        percentCoupon(percent, 10)
        val rounding = newShopper()
        fillCart(rounding, price = "1234.55", quantity = 1) // 10% = 123.455 → 123.46
        val rounded = placeOrder(rounding, percent)
        assertMoney("123.46", rounded["discountAmount"])
        assertMoney("2611.09", rounded["total"]) // 1,234.55 - 123.46 + 1,500

        val fixed = uniqueCode()
        fixedCoupon(fixed, 50000)
        val shopper = newShopper()
        fillCart(shopper) // subtotal 20,000
        val order = placeOrder(shopper, fixed)
        assertMoney("20000", order["discountAmount"])
        assertMoney("1500", order["total"])
    }

    @Test
    fun `every coupon that cannot be used is refused with its reason, the cart is kept and no use is counted`() {
        val shopper = newShopper()
        fillCart(shopper) // subtotal 20,000

        fun expectRefused(code: String, message: String) {
            checkout(shopper, code).andExpect {
                responseCode("106")
                jsonPath("$.responseMessage") { value(message) }
                jsonPath("$.data.fieldErrors[0].field") { value("couponCode") }
                jsonPath("$.data.fieldErrors[0].message") { value(message) }
            }
            preview(shopper, code).andExpect {
                responseCode("106")
                jsonPath("$.responseMessage") { value(message) }
            }
        }

        val unknown = uniqueCode()
        expectRefused(unknown.lowercase(), "Coupon $unknown does not exist")

        val deleted = uniqueCode()
        val deletedId = percentCoupon(deleted, 10)
        mockMvc.delete("/api/admin/coupons/$deletedId") { header("Authorization", "Bearer $adminToken") }
            .andExpect { responseCode("00") }
        expectRefused(deleted, "Coupon $deleted does not exist")

        val inactive = uniqueCode()
        setCouponColumn(percentCoupon(inactive, 10), "couponstatus", "INACTIVE")
        expectRefused(inactive, "Coupon $inactive is not active")

        val scheduled = uniqueCode()
        val startsAt = platformNow().plusDays(2).withHour(9).withMinute(30).withSecond(0).withNano(0)
        percentCoupon(scheduled, 10, "startsAt" to startsAt.toString())
        expectRefused(scheduled, "Coupon $scheduled can be used from ${startsAt.toLocalDate()} 09:30")

        val expired = uniqueCode()
        val expiredId = percentCoupon(expired, 10)
        setCouponColumn(expiredId, "couponexpiresat", platformNow().minusMinutes(1))
        expectRefused(expired, "Coupon $expired has expired")

        val exhausted = uniqueCode()
        val exhaustedId = percentCoupon(exhausted, 10, "usageLimit" to 3)
        setCouponColumn(exhaustedId, "couponusagecount", 3)
        expectRefused(exhausted, "Coupon $exhausted has reached its usage limit")

        val minimum = uniqueCode()
        val minimumId = percentCoupon(minimum, 10, "minimumOrderValue" to 25000)
        expectRefused(minimum, "Coupon $minimum needs a subtotal of at least ₦25,000.00 (yours is ₦20,000.00)")

        assertEquals(1, cartSize(shopper))
        assertEquals(3, usageCount(exhaustedId))
        assertEquals(0, usageCount(minimumId))
        assertEquals(0, usageCount(expiredId))
    }

    @Test
    fun `the preview shows what checkout will take off and counts no use`() {
        val code = uniqueCode()
        val couponId = percentCoupon(code, 15, "usageLimit" to 1)
        val shopper = newShopper()

        preview(shopper, code).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Cart is empty") }
        }

        fillCart(shopper) // 20,000; 15% = 3,000
        repeat(2) {
            preview(shopper, code.lowercase()).andExpect {
                responseCode("00")
                jsonPath("$.data.code") { value(code) }
                jsonPath("$.data.subtotal") { value(20000.0) }
                jsonPath("$.data.discountAmount") { value(3000.0) }
                jsonPath("$.data.subtotalAfterDiscount") { value(17000.0) }
            }
        }
        assertEquals(0, usageCount(couponId))

        assertMoney("3000", placeOrder(shopper, code)["discountAmount"])
        assertEquals(1, usageCount(couponId))

        preview(newShopper(), code).andExpect { responseCode("106") } // empty cart is checked first
        mockMvc.post("/api/customer/coupons/validate") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("code" to code)
        }.andExpect { responseCode("107") }
        preview(shopper, " ").andExpect { responseCode("106") }
    }

    @Test
    fun `a retry with the same Idempotency-Key does not count the coupon twice`() {
        val code = uniqueCode()
        val couponId = percentCoupon(code, 10, "usageLimit" to 5)
        val shopper = newShopper()
        fillCart(shopper)
        val key = UUID.randomUUID()

        checkout(shopper, code, key).andExpect { responseCode("00"); jsonPath("$.responseMessage") { value("Order created") } }
        checkout(shopper, code, key).andExpect {
            responseCode("00"); jsonPath("$.responseMessage") { value("Successful") }
            jsonPath("$.data.couponCode") { value(code) }
        }
        assertEquals(1, usageCount(couponId))
    }

    @Test
    fun `when five customers race for the last use, exactly one gets it`() {
        val code = uniqueCode()
        val couponId = percentCoupon(code, 10, "usageLimit" to 1)
        val shoppers = List(5) { newShopper().also { fillCart(it) } }

        val pool = Executors.newFixedThreadPool(5)
        val results = try {
            pool.invokeAll(shoppers.map { shopper ->
                Callable { checkout(shopper, code).andReturn().response.let { jsonMapper.readTree(it.contentAsString).let { b -> b["responseCode"].asString() to it.contentAsString } } }
            }).map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(listOf("00", "106", "106", "106", "106"), results.map { it.first }.sorted())
        results.filter { it.first == "106" }.forEach { (_, body) ->
            assertEquals("Coupon $code has reached its usage limit", jsonMapper.readTree(body)["responseMessage"].asString())
        }
        assertEquals(1, usageCount(couponId))
    }

    @Test
    fun `cancelling an unpaid order gives the use back, so another customer can use it`() {
        val code = uniqueCode()
        val couponId = percentCoupon(code, 10, "usageLimit" to 1)
        val first = newShopper().also { fillCart(it) }
        val second = newShopper().also { fillCart(it) }

        val order = placeOrder(first, code)
        checkout(second, code).andExpect { responseCode("106") }

        customerCancel(first, order["orderId"].asLong())
        assertEquals(0, usageCount(couponId))
        val secondOrder = placeOrder(second, code)
        assertEquals(code, secondOrder["couponCode"].asString())
        assertEquals(1, usageCount(couponId))

        // An admin cancelling an unpaid order gives it back too.
        val third = newShopper().also { fillCart(it) }
        adminCancel(secondOrder["orderId"].asLong())
        assertEquals(0, usageCount(couponId))
        placeOrder(third, code)
        assertEquals(1, usageCount(couponId))
    }

    @Test
    fun `a paid order that is cancelled keeps its use`() {
        val code = uniqueCode()
        val couponId = percentCoupon(code, 10)
        val shopper = newShopper().also { fillCart(it) }
        val order = placeOrder(shopper, code)
        pay(shopper, order)

        adminCancel(order["orderId"].asLong())
        assertEquals(1, usageCount(couponId))
    }

    @Test
    fun `a coupon that would make the order free is refused`() {
        val freeDelivery = createDeliveryMethod(0)
        val full = uniqueCode()
        val fullId = percentCoupon(full, 100)
        val shopper = newShopper()
        fillCart(shopper)

        checkout(shopper, full, delivery = freeDelivery).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Coupon $full would make the order free, which is not supported") }
            jsonPath("$.data.fieldErrors[0].field") { value("couponCode") }
        }
        assertEquals(0, usageCount(fullId))
        assertEquals(1, cartSize(shopper))

        // With a delivery fee to pay, the same coupon is fine: only the subtotal is free.
        val order = placeOrder(shopper, full)
        assertMoney("1500", order["total"])
        assertEquals(1, usageCount(fullId))
    }
}
