package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID

/** The customer cart (checkpoint 1.3, Step 3). Each test uses a fresh customer and its own category. */
class CartTest : IntegrationTestBase() {

    private lateinit var adminToken: String
    private lateinit var token: String
    private var categoryId: Int = 0

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        token = newCustomerToken()
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

    private fun createProduct(price: Int = 1000, discountPrice: Int? = null, stock: Int = 10): Long {
        val result = mockMvc.post("/dev/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "name" to unique("Phone"),
                "sku" to unique("sku"),
                "categoryId" to categoryId,
                "price" to price,
                "discountPrice" to discountPrice,
                "stockQuantity" to stock,
            )
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result)["productId"].asLong()
    }

    private fun admin(method: String, url: String, body: String? = null) {
        val auth = "Bearer $adminToken"
        when (method) {
            "PATCH" -> mockMvc.patch(url) {
                header("Authorization", auth)
                contentType = MediaType.APPLICATION_JSON
                content = body!!
            }
            else -> mockMvc.delete(url) { header("Authorization", auth) }
        }.andExpect { responseCode("00") }
    }

    private fun addItem(body: String, withToken: String = token) = mockMvc.post("/dev/customer/cart/items") {
        header("Authorization", "Bearer $withToken")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun patchItem(productId: Long, body: String) = mockMvc.patch("/dev/customer/cart/items/$productId") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun getCart(withToken: String = token) = mockMvc.get("/dev/customer/cart") {
        header("Authorization", "Bearer $withToken")
    }

    @Test
    fun `a new customer has an empty cart that cannot be checked out`() {
        getCart().andExpect {
            responseCode("00")
            jsonPath("$.data.items.length()") { value(0) }
            jsonPath("$.data.itemCount") { value(0) }
            jsonPath("$.data.subtotal") { value(0.00) }
            jsonPath("$.data.canCheckout") { value(false) }
        }
    }

    @Test
    fun `adding twice increases one line, and prices come from the database, not the request`() {
        val productId = createProduct(price = 1000, discountPrice = 800)

        // Quantity defaults to 1; the sent price is ignored.
        addItem(json("productId" to productId, "price" to 1)).andExpect {
            responseCode("00")
            jsonPath("$.data.items[0].quantity") { value(1) }
            jsonPath("$.data.items[0].unitPrice") { value(800.00) }
            jsonPath("$.data.subtotal") { value(800.00) }
        }
        addItem(json("productId" to productId, "quantity" to 2, "unitPrice" to 1, "subtotal" to 1)).andExpect {
            responseCode("00")
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].quantity") { value(3) }
            jsonPath("$.data.items[0].price") { value(1000.00) }
            jsonPath("$.data.items[0].discountPrice") { value(800.00) }
            jsonPath("$.data.items[0].lineTotal") { value(2400.00) }
            jsonPath("$.data.items[0].available") { value(true) }
            jsonPath("$.data.items[0].issue") { value(null) }
            jsonPath("$.data.itemCount") { value(3) }
            jsonPath("$.data.subtotal") { value(2400.00) }
            jsonPath("$.data.canCheckout") { value(true) }
        }
    }

    @Test
    fun `the subtotal adds up several products`() {
        val phone = createProduct(price = 1000)
        val case = createProduct(price = 250, discountPrice = 200)
        addItem(json("productId" to phone, "quantity" to 2))
        addItem(json("productId" to case, "quantity" to 3)).andExpect {
            jsonPath("$.data.items.length()") { value(2) }
            jsonPath("$.data.itemCount") { value(5) }
            jsonPath("$.data.subtotal") { value(2600.00) }
        }
    }

    @Test
    fun `a customer cannot add more than the available stock`() {
        val productId = createProduct(stock = 5)

        addItem(json("productId" to productId, "quantity" to 6)).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Only 5 available") }
        }
        // Exactly the stock is fine.
        addItem(json("productId" to productId, "quantity" to 5)).andExpect { responseCode("00") }
        // The cart already holds all 5, so one more is too many, and the cart is unchanged.
        addItem(json("productId" to productId, "quantity" to 1)).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Only 5 available") }
        }
        // A huge quantity cannot overflow past the check.
        addItem(json("productId" to productId, "quantity" to Int.MAX_VALUE)).andExpect { responseCode("106") }
        getCart().andExpect { jsonPath("$.data.items[0].quantity") { value(5) } }
    }

    @Test
    fun `an out-of-stock product cannot be added`() {
        val productId = createProduct(stock = 0)
        addItem(json("productId" to productId)).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Out of stock") }
        }
    }

    @Test
    fun `hidden and unknown products cannot be added`() {
        val inactive = createProduct()
        admin("PATCH", "/dev/admin/products/$inactive/status", json("status" to "INACTIVE"))
        val deleted = createProduct()
        admin("DELETE", "/dev/admin/products/$deleted")

        listOf(inactive, deleted, 999999L).forEach { productId ->
            addItem(json("productId" to productId)).andExpect {
                responseCode("25")
                jsonPath("$.responseMessage") { value("Product not found") }
            }
        }
        getCart().andExpect { jsonPath("$.data.items.length()") { value(0) } }
    }

    @Test
    fun `invalid add requests return 400`() {
        addItem(json("quantity" to 1)).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].field") { value("productId") }
        }
        addItem(json("productId" to createProduct(), "quantity" to 0)).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].field") { value("quantity") }
        }
    }

    @Test
    fun `PATCH sets the quantity within the stock`() {
        val productId = createProduct(stock = 5)
        addItem(json("productId" to productId, "quantity" to 4))

        patchItem(productId, json("quantity" to 2)).andExpect {
            responseCode("00")
            jsonPath("$.data.items[0].quantity") { value(2) }
            jsonPath("$.data.subtotal") { value(2000.00) }
        }
        patchItem(productId, json("quantity" to 6)).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("Only 5 available") }
        }
        patchItem(productId, json("quantity" to 0)).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].field") { value("quantity") }
        }
        patchItem(productId, json("quantity" to 3, "price" to 1)).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].message") { value("only quantity can be updated") }
        }
        getCart().andExpect { jsonPath("$.data.items[0].quantity") { value(2) } }
    }

    @Test
    fun `PATCH and DELETE on a product not in the cart return 404`() {
        val productId = createProduct()
        patchItem(productId, json("quantity" to 1)).andExpect {
            responseCode("25")
            jsonPath("$.responseMessage") { value("Item not in cart") }
        }
        mockMvc.delete("/dev/customer/cart/items/$productId") {
            header("Authorization", "Bearer $token")
        }.andExpect { responseCode("25") }
    }

    @Test
    fun `items can be removed one by one or all at once`() {
        val first = createProduct()
        val second = createProduct()
        val third = createProduct()
        listOf(first, second, third).forEach { addItem(json("productId" to it)) }

        mockMvc.delete("/dev/customer/cart/items/$first") {
            header("Authorization", "Bearer $token")
        }.andExpect { responseCode("00") }
        getCart().andExpect {
            jsonPath("$.data.items.length()") { value(2) }
            jsonPath("$.data.items[0].productId") { value(second) }
        }

        mockMvc.delete("/dev/customer/cart") {
            header("Authorization", "Bearer $token")
        }.andExpect { responseCode("00") }
        getCart().andExpect {
            jsonPath("$.data.items.length()") { value(0) }
            jsonPath("$.data.canCheckout") { value(false) }
        }
    }

    @Test
    fun `items that can no longer be bought are flagged and left out of the subtotal`() {
        val fine = createProduct(price = 100)
        val deactivated = createProduct()
        val deleted = createProduct()
        val lowered = createProduct(stock = 10)
        val soldOut = createProduct(stock = 10)
        addItem(json("productId" to fine))
        addItem(json("productId" to deactivated))
        addItem(json("productId" to deleted))
        addItem(json("productId" to lowered, "quantity" to 4))
        addItem(json("productId" to soldOut))

        admin("PATCH", "/dev/admin/products/$deactivated/status", json("status" to "INACTIVE"))
        admin("DELETE", "/dev/admin/products/$deleted")
        admin("PATCH", "/dev/admin/products/$lowered", json("stockQuantity" to 3))
        admin("PATCH", "/dev/admin/products/$soldOut", json("stockQuantity" to 0))

        getCart().andExpect {
            responseCode("00")
            jsonPath("$.data.items.length()") { value(5) }
            jsonPath("$.data.items[0].issue") { value(null) }
            jsonPath("$.data.items[1].issue") { value("PRODUCT_UNAVAILABLE") }
            jsonPath("$.data.items[2].issue") { value("PRODUCT_UNAVAILABLE") }
            jsonPath("$.data.items[3].issue") { value("INSUFFICIENT_STOCK") }
            jsonPath("$.data.items[3].availableQuantity") { value(3) }
            jsonPath("$.data.items[3].available") { value(false) }
            jsonPath("$.data.items[4].issue") { value("OUT_OF_STOCK") }
            jsonPath("$.data.items[4].availableQuantity") { value(null) }
            jsonPath("$.data.itemCount") { value(1) }
            jsonPath("$.data.subtotal") { value(100.00) }
            jsonPath("$.data.canCheckout") { value(false) }
        }

        // Fixing the cart makes it checkable again.
        patchItem(lowered, json("quantity" to 3)).andExpect { responseCode("00") }
        listOf(deactivated, deleted, soldOut).forEach {
            mockMvc.delete("/dev/customer/cart/items/$it") { header("Authorization", "Bearer $token") }
        }
        getCart().andExpect {
            jsonPath("$.data.items.length()") { value(2) }
            jsonPath("$.data.subtotal") { value(3100.00) }
            jsonPath("$.data.canCheckout") { value(true) }
        }
    }

    @Test
    fun `deactivating the category flags its products in the cart`() {
        val productId = createProduct()
        addItem(json("productId" to productId))
        admin("PATCH", "/dev/admin/categories/$categoryId/status", json("status" to "INACTIVE"))

        getCart().andExpect {
            jsonPath("$.data.items[0].issue") { value("PRODUCT_UNAVAILABLE") }
            jsonPath("$.data.canCheckout") { value(false) }
        }
    }

    @Test
    fun `each customer sees only their own cart`() {
        addItem(json("productId" to createProduct()))
        val other = newCustomerToken()

        getCart(other).andExpect { jsonPath("$.data.items.length()") { value(0) } }
        getCart().andExpect { jsonPath("$.data.items.length()") { value(1) } }
    }

    @Test
    fun `the cart needs a customer token`() {
        mockMvc.get("/dev/customer/cart").andExpect { responseCode("115") }
        addItem(json("productId" to createProduct()), withToken = adminToken).andExpect { responseCode("107") }
    }
}
