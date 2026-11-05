package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

/** Customer delivery addresses (checkpoint 1.3, Step 2). Each test uses a fresh customer. */
class AddressTest : IntegrationTestBase() {

    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        token = newCustomerToken()
    }

    private fun addressBody(vararg overrides: Pair<String, Any?>): String {
        val fields = mutableMapOf<String, Any?>(
            "recipientName" to "Ada Obi",
            "recipientPhoneNumber" to "08012345678",
            "addressLine1" to "12 Allen Avenue",
            "city" to "Ikeja",
            "state" to "Lagos",
            "country" to "Nigeria",
        )
        overrides.forEach { (key, value) -> fields[key] = value }
        return jsonMapper.writeValueAsString(fields)
    }

    private fun postAddress(body: String, withToken: String = token) = mockMvc.post("/dev/customer/addresses") {
        header("Authorization", "Bearer $withToken")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun createAddress(vararg overrides: Pair<String, Any?>): Long {
        val result = postAddress(addressBody(*overrides)).andExpect { responseCode("00") }.andReturn()
        return readJson(result)["addressId"].asLong()
    }

    private fun patchAddress(addressId: Long, body: String) = mockMvc.patch("/dev/customer/addresses/$addressId") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    /** The customer's addresses in list order, as "id:isDefault". */
    private fun listed(): List<String> {
        val result = mockMvc.get("/dev/customer/addresses") {
            header("Authorization", "Bearer $token")
        }.andExpect { responseCode("00") }.andReturn()
        return readJson(result).toList().map { "${it["addressId"].asLong()}:${it["isDefault"].asBoolean()}" }
    }

    @Test
    fun `create trims the fields and the first address becomes the default`() {
        postAddress(addressBody("recipientName" to "  Ada Obi  ", "city" to " Ikeja ", "postalCode" to "  ")).andExpect {
            responseCode("00")
            jsonPath("$.data.addressId") { isNumber() }
            jsonPath("$.data.recipientName") { value("Ada Obi") }
            jsonPath("$.data.city") { value("Ikeja") }
            jsonPath("$.data.addressLine2") { value(null) }
            jsonPath("$.data.postalCode") { value(null) }
            // Even though isDefault was not sent.
            jsonPath("$.data.isDefault") { value(true) }
        }
    }

    @Test
    fun `later addresses are not the default unless asked, and the list shows the default first`() {
        val first = createAddress()
        val second = createAddress()
        val third = createAddress()

        assertEquals(listOf("$first:true", "$third:false", "$second:false"), listed())
    }

    @Test
    fun `missing required fields and a bad phone number return 400`() {
        postAddress(addressBody("recipientName" to null, "city" to "   ")).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors.length()") { value(2) }
        }
        postAddress(addressBody("recipientPhoneNumber" to "123")).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].field") { value("recipientPhoneNumber") }
        }
    }

    @Test
    fun `PATCH changes only the fields sent and empty optional fields are cleared`() {
        val addressId = createAddress("addressLine2" to "Flat 3", "postalCode" to "100001")

        patchAddress(addressId, json("city" to " Yaba ", "addressLine2" to "")).andExpect {
            responseCode("00")
            jsonPath("$.data.city") { value("Yaba") }
            jsonPath("$.data.addressLine2") { value(null) }
            jsonPath("$.data.postalCode") { value("100001") }
            jsonPath("$.data.recipientName") { value("Ada Obi") }
            jsonPath("$.data.isDefault") { value(true) }
        }
    }

    @Test
    fun `PATCH rejects blank required fields, unknown fields, isDefault and empty bodies`() {
        val addressId = createAddress()

        patchAddress(addressId, json("city" to "  ")).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].field") { value("city") }
        }
        // Rejected even next to a valid field, and nothing is changed.
        patchAddress(addressId, json("city" to "Yaba", "isDefault" to false)).andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors.length()") { value(1) }
            jsonPath("$.data.fieldErrors[0].message") { value(containsString("PATCH .../default")) }
        }
        mockMvc.get("/dev/customer/addresses/$addressId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            jsonPath("$.data.city") { value("Ikeja") }
            jsonPath("$.data.isDefault") { value(true) }
        }
        patchAddress(addressId, "{}").andExpect {
            responseCode("106")
            jsonPath("$.data.fieldErrors[0].message") { value("send at least one field to update") }
        }
    }

    @Test
    fun `isDefault on create and the default endpoint both move the default, keeping only one`() {
        val first = createAddress()
        val second = createAddress("isDefault" to true)
        assertEquals(listOf("$second:true", "$first:false"), listed())

        mockMvc.patch("/dev/customer/addresses/$first/default") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            responseCode("00")
            jsonPath("$.data.isDefault") { value(true) }
        }
        assertEquals(listOf("$first:true", "$second:false"), listed())

        // Setting the current default again changes nothing.
        mockMvc.patch("/dev/customer/addresses/$first/default") {
            header("Authorization", "Bearer $token")
        }.andExpect { responseCode("00") }
        assertEquals(listOf("$first:true", "$second:false"), listed())
    }

    @Test
    fun `deleting the default leaves no default`() {
        val first = createAddress()
        val second = createAddress()

        mockMvc.delete("/dev/customer/addresses/$first") {
            header("Authorization", "Bearer $token")
        }.andExpect { responseCode("00") }

        assertEquals(listOf("$second:false"), listed())
        mockMvc.get("/dev/customer/addresses/$first") {
            header("Authorization", "Bearer $token")
        }.andExpect { responseCode("25") }
    }

    @Test
    fun `another customer's address is not found and stays unchanged`() {
        val addressId = createAddress()
        val other = newCustomerToken()

        mockMvc.get("/dev/customer/addresses/$addressId") {
            header("Authorization", "Bearer $other")
        }.andExpect {
            responseCode("25")
            jsonPath("$.responseMessage") { value("Address not found") }
        }
        mockMvc.patch("/dev/customer/addresses/$addressId") {
            header("Authorization", "Bearer $other")
            contentType = MediaType.APPLICATION_JSON
            content = json("city" to "Stolen")
        }.andExpect { responseCode("25") }
        mockMvc.patch("/dev/customer/addresses/$addressId/default") {
            header("Authorization", "Bearer $other")
        }.andExpect { responseCode("25") }
        mockMvc.delete("/dev/customer/addresses/$addressId") {
            header("Authorization", "Bearer $other")
        }.andExpect { responseCode("25") }

        // The other customer's own list is empty, and the owner's address is untouched.
        mockMvc.get("/dev/customer/addresses") {
            header("Authorization", "Bearer $other")
        }.andExpect { jsonPath("$.data.length()") { value(0) } }
        mockMvc.get("/dev/customer/addresses/$addressId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            responseCode("00")
            jsonPath("$.data.city") { value("Ikeja") }
            jsonPath("$.data.isDefault") { value(true) }
        }
    }

    @Test
    fun `an unknown address returns 404`() {
        mockMvc.get("/dev/customer/addresses/999999") {
            header("Authorization", "Bearer $token")
        }.andExpect { responseCode("25") }
        mockMvc.delete("/dev/customer/addresses/999999") {
            header("Authorization", "Bearer $token")
        }.andExpect { responseCode("25") }
    }

    @Test
    fun `a customer can save at most 10 addresses`() {
        repeat(10) { createAddress() }

        postAddress(addressBody()).andExpect {
            responseCode("106")
            jsonPath("$.responseMessage") { value("You can save at most 10 addresses") }
        }
        assertEquals(10, listed().size)
    }

    @Test
    fun `addresses need a customer token`() {
        mockMvc.get("/dev/customer/addresses").andExpect { responseCode("115") }
        postAddress(addressBody(), withToken = loginActiveAdmin()).andExpect { responseCode("107") }
    }
}
