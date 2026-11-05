package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Delivery methods and fees (checkpoint 2.2, Step 1): admin management, the public list, and checkout totals. */
class DeliveryMethodTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private var categoryId: Int = 0

    private data class Shopper(val email: String, val token: String)

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        categoryId = createCategory()
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun uniqueCode() = "DM_${UUID.randomUUID().toString().take(8).uppercase()}"

    private fun create(body: String, token: String = adminToken): ResultActionsDsl =
        mockMvc.post("/dev/admin/delivery-methods") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    /** Creates an active method and returns its id. */
    private fun createMethod(code: String, fee: Int, name: String = "Express"): Int =
        readJson(
            create(json("code" to code, "name" to name, "fee" to fee, "estimatedMinDays" to 1, "estimatedMaxDays" to 2))
                .andExpect { responseCode("00") }.andReturn()
        )["deliveryMethodId"].asInt()

    private fun update(id: Int, body: String): ResultActionsDsl =
        mockMvc.patch("/dev/admin/delivery-methods/$id") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun setStatus(id: Int, status: String): ResultActionsDsl =
        mockMvc.patch("/dev/admin/delivery-methods/$id/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to status)
        }

    private fun publicCodes(): List<String> {
        val list = readJson(mockMvc.get("/dev/delivery-methods").andExpect { responseCode("00") }.andReturn())
        return (0 until list.size()).map { list[it]["code"].asString() }
    }

    private fun createCategory(): Int {
        val result = mockMvc.post("/dev/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    private fun newShopper(): Shopper {
        val email = uniqueEmail()
        registerCustomer(email)
        val shopper = Shopper(email, loginCustomer(email))
        mockMvc.post("/dev/customer/addresses") {
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

    /** Puts 2 units of a new 1,000 product in the cart (subtotal 2,000). */
    private fun fillCart(shopper: Shopper) {
        val product = mockMvc.post("/dev/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Phone"), "sku" to unique("sku"), "categoryId" to categoryId, "price" to 1000, "stockQuantity" to 10)
        }.andExpect { responseCode("00") }.andReturn()
        mockMvc.post("/dev/customer/cart/items") {
            header("Authorization", "Bearer ${shopper.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to readJson(product)["productId"].asLong(), "quantity" to 2)
        }.andExpect { responseCode("00") }
    }

    private fun checkout(shopper: Shopper, body: String? = null): ResultActionsDsl =
        mockMvc.post("/dev/customer/orders") {
            header("Authorization", "Bearer ${shopper.token}")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body?.let {
                contentType = MediaType.APPLICATION_JSON
                content = it
            }
        }

    private fun placeOrder(shopper: Shopper, deliveryMethodCode: String?): JsonNode {
        fillCart(shopper)
        val body = deliveryMethodCode?.let { json("deliveryMethodCode" to it) }
        return readJson(checkout(shopper, body).andExpect { responseCode("00"); jsonPath("$.responseMessage") { value("Order created") } }.andReturn())
    }

    private fun standardId(): Int =
        jdbcClient.sql("SELECT deliverymethodid FROM public.ec_delivery_method WHERE deliverymethodcode = 'STANDARD'")
            .query(Int::class.javaObjectType).single()

    @Test
    fun `an admin creates a method with an uppercase code, and codes are unique ignoring case`() {
        val code = uniqueCode()
        create(json("code" to " ${code.lowercase()} ", "name" to " Express ", "description" to "Lagos only", "fee" to 2500, "estimatedMinDays" to 1, "estimatedMaxDays" to 2))
            .andExpect {
                responseCode("00")
                jsonPath("$.data.code") { value(code) }
                jsonPath("$.data.name") { value("Express") }
                jsonPath("$.data.fee") { value(2500.00) }
                jsonPath("$.data.status") { value("ACTIVE") }
            }

        create(json("code" to code.lowercase(), "name" to "Again", "fee" to 1)).andExpect {
            responseCode("117")
            jsonPath("$.responseMessage") { value("Delivery method code is already in use") }
        }
    }

    @Test
    fun `invalid methods are rejected`() {
        create(json("code" to uniqueCode(), "name" to "X", "fee" to -1)).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[*].field") { value(hasItem("fee")) }
        }
        create(json("code" to uniqueCode(), "name" to "X", "fee" to 10.555)).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[*].field") { value(hasItem("fee")) }
        }
        create(json("code" to "has space", "name" to "X", "fee" to 0)).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[*].field") { value(hasItem("code")) }
        }
        create(json("code" to uniqueCode(), "name" to "X", "fee" to 0, "estimatedMinDays" to 5, "estimatedMaxDays" to 2)).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("estimatedMaxDays must not be less than estimatedMinDays") }
        }

        val id = createMethod(uniqueCode(), 100)
        // Only one of the two days is sent: it is compared with the stored other one (max 2).
        update(id, json("estimatedMinDays" to 3)).andExpect { responseCode("106") }
        update(id, json("code" to "NEWCODE")).andExpect { responseCode("106") }
        update(id, json("status" to "INACTIVE")).andExpect { responseCode("106") }
        update(id, "{}").andExpect { responseCode("106") }
    }

    @Test
    fun `the public list shows only active methods, cheapest first, without admin fields and without a token`() {
        val cheap = uniqueCode()
        val dear = uniqueCode()
        val hidden = uniqueCode()
        createMethod(dear, 9000)
        createMethod(cheap, 500)
        val hiddenId = createMethod(hidden, 700)
        setStatus(hiddenId, "inactive").andExpect {
            responseCode("00")
            jsonPath("$.data.status") { value("INACTIVE") }
        }

        val codes = publicCodes()
        assertTrue(codes.indexOf(cheap) in 0 until codes.indexOf(dear), "cheapest first: $codes")
        assertFalse(hidden in codes)

        mockMvc.get("/dev/delivery-methods").andExpect {
            responseCode("00")
            jsonPath("$.data[0].deliveryMethodId") { doesNotExist() }
            jsonPath("$.data[0].status") { doesNotExist() }
        }

        // The admin list still shows it, so it can be reactivated.
        mockMvc.get("/dev/admin/delivery-methods") { header("Authorization", "Bearer $adminToken") }
            .andExpect { jsonPath("$.data[*].code") { value(hasItem(hidden)) } }
        setStatus(hiddenId, "ACTIVE").andExpect { responseCode("00") }
        assertTrue(hidden in publicCodes())
    }

    @Test
    fun `STANDARD cannot be deactivated`() {
        setStatus(standardId(), "INACTIVE").andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("STANDARD is the default delivery method and cannot be deactivated") }
        }
        assertTrue("STANDARD" in publicCodes())
    }

    @Test
    fun `checkout adds the chosen method's fee to the total and Paystack is asked for that total`() {
        val code = uniqueCode()
        createMethod(code, 2500)
        val shopper = newShopper()

        val order = placeOrder(shopper, code.lowercase())
        assertEquals(0, BigDecimal("2000").compareTo(order["subtotal"].decimalValue()))
        assertEquals(0, BigDecimal("2500").compareTo(order["deliveryFee"].decimalValue()))
        assertEquals(0, BigDecimal("4500").compareTo(order["total"].decimalValue()))
        assertEquals(code, order["deliveryMethod"]["code"].asString())
        assertEquals("Express", order["deliveryMethod"]["name"].asString())

        val payment = readJson(
            mockMvc.post("/dev/customer/payments/initialize") {
                header("Authorization", "Bearer ${shopper.token}")
                contentType = MediaType.APPLICATION_JSON
                content = json("orderReference" to order["reference"].asString())
            }.andExpect { responseCode("00") }.andReturn()
        )
        val sent = paymentGateway.initialized.single { it.reference == payment["paymentReference"].asString() }
        assertEquals(450_000L, sent.amountKobo)

        // The admin sees the same method on the order.
        mockMvc.get("/dev/admin/orders/${order["orderId"].asLong()}") { header("Authorization", "Bearer $adminToken") }
            .andExpect { jsonPath("$.data.deliveryMethod.code") { value(code) } }
    }

    @Test
    fun `checkout without a method uses STANDARD`() {
        val order = placeOrder(newShopper(), deliveryMethodCode = null)

        assertEquals("STANDARD", order["deliveryMethod"]["code"].asString())
        assertEquals(0, order["total"].decimalValue().compareTo(order["subtotal"].decimalValue() + order["deliveryFee"].decimalValue()))
    }

    @Test
    fun `an unknown or inactive method is refused at checkout and the cart is kept`() {
        val shopper = newShopper()
        fillCart(shopper)

        checkout(shopper, json("deliveryMethodCode" to "NOPE")).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Delivery method NOPE is not available") }
        }

        val code = uniqueCode()
        setStatus(createMethod(code, 100), "INACTIVE")
        checkout(shopper, json("deliveryMethodCode" to code)).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Delivery method $code is not available") }
        }

        mockMvc.get("/dev/customer/cart") { header("Authorization", "Bearer ${shopper.token}") }
            .andExpect { jsonPath("$.data.items.length()") { value(1) } }
    }

    @Test
    fun `a new fee applies to new orders only`() {
        val code = uniqueCode()
        val id = createMethod(code, 1000)
        val shopper = newShopper()
        val first = placeOrder(shopper, code)

        update(id, json("fee" to 1500, "description" to "")).andExpect {
            responseCode("00")
            jsonPath("$.data.fee") { value(1500.00) }
            jsonPath("$.data.description") { value(null) }
            jsonPath("$.data.estimatedMaxDays") { value(2) }
        }
        val second = placeOrder(shopper, code)

        val firstAgain = readJson(
            mockMvc.get("/dev/customer/orders/${first["orderId"].asLong()}") { header("Authorization", "Bearer ${shopper.token}") }
                .andExpect { responseCode("00") }.andReturn()
        )
        assertEquals(0, BigDecimal("1000").compareTo(firstAgain["deliveryFee"].decimalValue()))
        assertEquals(0, BigDecimal("3000").compareTo(firstAgain["total"].decimalValue()))
        assertEquals(0, BigDecimal("1500").compareTo(second["deliveryFee"].decimalValue()))
        assertEquals(0, BigDecimal("3500").compareTo(second["total"].decimalValue()))
    }

    @Test
    fun `customers cannot manage delivery methods`() {
        val customerToken = newCustomerToken()
        create(json("code" to uniqueCode(), "name" to "X", "fee" to 0), token = customerToken).andExpect { responseCode("107") }
        mockMvc.get("/dev/admin/delivery-methods") { header("Authorization", "Bearer $customerToken") }
            .andExpect { responseCode("107") }
        mockMvc.post("/dev/admin/delivery-methods") {
            contentType = MediaType.APPLICATION_JSON
            content = json("code" to uniqueCode(), "name" to "X", "fee" to 0)
        }.andExpect { responseCode("115") }
    }
}
