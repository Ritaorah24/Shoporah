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
        mockMvc.post("/api/admin/delivery-methods") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    /** Creates an active method and returns its id. */
    private fun createMethod(code: String, fee: Int, name: String = "Express"): Int =
        readJson(
            create(json("code" to code, "name" to name, "fee" to fee, "estimatedMinDays" to 1, "estimatedMaxDays" to 2))
                .andExpect { status { isCreated() } }.andReturn()
        )["deliveryMethodId"].asInt()

    private fun update(id: Int, body: String): ResultActionsDsl =
        mockMvc.patch("/api/admin/delivery-methods/$id") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun setStatus(id: Int, status: String): ResultActionsDsl =
        mockMvc.patch("/api/admin/delivery-methods/$id/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to status)
        }

    private fun publicCodes(): List<String> {
        val list = readJson(mockMvc.get("/api/delivery-methods").andExpect { status { isOk() } }.andReturn())
        return (0 until list.size()).map { list[it]["code"].asString() }
    }

    private fun createCategory(): Int {
        val result = mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["categoryId"].asInt()
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
        }.andExpect { status { isCreated() } }
        return shopper
    }

    /** Puts 2 units of a new 1,000 product in the cart (subtotal 2,000). */
    private fun fillCart(shopper: Shopper) {
        val product = mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Phone"), "sku" to unique("sku"), "categoryId" to categoryId, "price" to 1000, "stockQuantity" to 10)
        }.andExpect { status { isCreated() } }.andReturn()
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer ${shopper.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to readJson(product)["productId"].asLong(), "quantity" to 2)
        }.andExpect { status { isOk() } }
    }

    private fun checkout(shopper: Shopper, body: String? = null): ResultActionsDsl =
        mockMvc.post("/api/customer/orders") {
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
        return readJson(checkout(shopper, body).andExpect { status { isCreated() } }.andReturn())
    }

    private fun standardId(): Int =
        jdbcClient.sql("SELECT deliverymethodid FROM public.ec_delivery_method WHERE deliverymethodcode = 'STANDARD'")
            .query(Int::class.javaObjectType).single()

    @Test
    fun `an admin creates a method with an uppercase code, and codes are unique ignoring case`() {
        val code = uniqueCode()
        create(json("code" to " ${code.lowercase()} ", "name" to " Express ", "description" to "Lagos only", "fee" to 2500, "estimatedMinDays" to 1, "estimatedMaxDays" to 2))
            .andExpect {
                status { isCreated() }
                jsonPath("$.code") { value(code) }
                jsonPath("$.name") { value("Express") }
                jsonPath("$.fee") { value(2500.00) }
                jsonPath("$.status") { value("ACTIVE") }
            }

        create(json("code" to code.lowercase(), "name" to "Again", "fee" to 1)).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("Delivery method code is already in use") }
        }
    }

    @Test
    fun `invalid methods are rejected`() {
        create(json("code" to uniqueCode(), "name" to "X", "fee" to -1)).andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors[*].field") { value(hasItem("fee")) }
        }
        create(json("code" to uniqueCode(), "name" to "X", "fee" to 10.555)).andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors[*].field") { value(hasItem("fee")) }
        }
        create(json("code" to "has space", "name" to "X", "fee" to 0)).andExpect {
            status { isBadRequest() }
            jsonPath("$.fieldErrors[*].field") { value(hasItem("code")) }
        }
        create(json("code" to uniqueCode(), "name" to "X", "fee" to 0, "estimatedMinDays" to 5, "estimatedMaxDays" to 2)).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("estimatedMaxDays must not be less than estimatedMinDays") }
        }

        val id = createMethod(uniqueCode(), 100)
        // Only one of the two days is sent: it is compared with the stored other one (max 2).
        update(id, json("estimatedMinDays" to 3)).andExpect { status { isBadRequest() } }
        update(id, json("code" to "NEWCODE")).andExpect { status { isBadRequest() } }
        update(id, json("status" to "INACTIVE")).andExpect { status { isBadRequest() } }
        update(id, "{}").andExpect { status { isBadRequest() } }
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
            status { isOk() }
            jsonPath("$.status") { value("INACTIVE") }
        }

        val codes = publicCodes()
        assertTrue(codes.indexOf(cheap) in 0 until codes.indexOf(dear), "cheapest first: $codes")
        assertFalse(hidden in codes)

        mockMvc.get("/api/delivery-methods").andExpect {
            status { isOk() }
            jsonPath("$[0].deliveryMethodId") { doesNotExist() }
            jsonPath("$[0].status") { doesNotExist() }
        }

        // The admin list still shows it, so it can be reactivated.
        mockMvc.get("/api/admin/delivery-methods") { header("Authorization", "Bearer $adminToken") }
            .andExpect { jsonPath("$[*].code") { value(hasItem(hidden)) } }
        setStatus(hiddenId, "ACTIVE").andExpect { status { isOk() } }
        assertTrue(hidden in publicCodes())
    }

    @Test
    fun `STANDARD cannot be deactivated`() {
        setStatus(standardId(), "INACTIVE").andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("STANDARD is the default delivery method and cannot be deactivated") }
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
            mockMvc.post("/api/customer/payments/initialize") {
                header("Authorization", "Bearer ${shopper.token}")
                contentType = MediaType.APPLICATION_JSON
                content = json("orderReference" to order["reference"].asString())
            }.andExpect { status { isCreated() } }.andReturn()
        )
        val sent = paymentGateway.initialized.single { it.reference == payment["paymentReference"].asString() }
        assertEquals(450_000L, sent.amountKobo)

        // The admin sees the same method on the order.
        mockMvc.get("/api/admin/orders/${order["orderId"].asLong()}") { header("Authorization", "Bearer $adminToken") }
            .andExpect { jsonPath("$.deliveryMethod.code") { value(code) } }
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
            status { isBadRequest() }
            jsonPath("$.message") { value("Delivery method NOPE is not available") }
        }

        val code = uniqueCode()
        setStatus(createMethod(code, 100), "INACTIVE")
        checkout(shopper, json("deliveryMethodCode" to code)).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Delivery method $code is not available") }
        }

        mockMvc.get("/api/customer/cart") { header("Authorization", "Bearer ${shopper.token}") }
            .andExpect { jsonPath("$.items.length()") { value(1) } }
    }

    @Test
    fun `a new fee applies to new orders only`() {
        val code = uniqueCode()
        val id = createMethod(code, 1000)
        val shopper = newShopper()
        val first = placeOrder(shopper, code)

        update(id, json("fee" to 1500, "description" to "")).andExpect {
            status { isOk() }
            jsonPath("$.fee") { value(1500.00) }
            jsonPath("$.description") { value(null) }
            jsonPath("$.estimatedMaxDays") { value(2) }
        }
        val second = placeOrder(shopper, code)

        val firstAgain = readJson(
            mockMvc.get("/api/customer/orders/${first["orderId"].asLong()}") { header("Authorization", "Bearer ${shopper.token}") }
                .andExpect { status { isOk() } }.andReturn()
        )
        assertEquals(0, BigDecimal("1000").compareTo(firstAgain["deliveryFee"].decimalValue()))
        assertEquals(0, BigDecimal("3000").compareTo(firstAgain["total"].decimalValue()))
        assertEquals(0, BigDecimal("1500").compareTo(second["deliveryFee"].decimalValue()))
        assertEquals(0, BigDecimal("3500").compareTo(second["total"].decimalValue()))
    }

    @Test
    fun `customers cannot manage delivery methods`() {
        val customerToken = newCustomerToken()
        create(json("code" to uniqueCode(), "name" to "X", "fee" to 0), token = customerToken).andExpect { status { isForbidden() } }
        mockMvc.get("/api/admin/delivery-methods") { header("Authorization", "Bearer $customerToken") }
            .andExpect { status { isForbidden() } }
        mockMvc.post("/api/admin/delivery-methods") {
            contentType = MediaType.APPLICATION_JSON
            content = json("code" to uniqueCode(), "name" to "X", "fee" to 0)
        }.andExpect { status { isUnauthorized() } }
    }
}
