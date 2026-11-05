package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Checkout runs in one transaction (checkpoint 1.3, Step 7, criterion 3b). A failure is forced at
 * the very last write of checkout (CartRepository.touch, after the order, its items, the history row
 * and the cart clearing were all written), and nothing of it may remain.
 */
class CheckoutRollbackTest : IntegrationTestBase() {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun count(sql: String, customerId: Long): Long =
        jdbcClient.sql(sql).param("customerId", customerId).query(Long::class.javaObjectType).single()

    private fun orders(customerId: Long) =
        count("SELECT COUNT(*) FROM public.ec_order WHERE ordercustomerid = :customerId", customerId)

    private fun orderItems(customerId: Long) = count(
        """
        SELECT COUNT(*) FROM public.ec_order_item i
        JOIN public.ec_order o ON o.orderid = i.orderitemorderid
        WHERE o.ordercustomerid = :customerId
        """.trimIndent(),
        customerId,
    )

    private fun historyRows(customerId: Long) =
        count("SELECT COUNT(*) FROM public.ec_order_status_history WHERE orderstatushistorychangedbycustomerid = :customerId", customerId)

    @Test
    fun `a failure late in checkout rolls back the order, its items, its history and the cart clearing`() {
        val adminToken = loginActiveAdmin()
        val email = uniqueEmail()
        val customerId = registerCustomer(email)
        val token = loginCustomer(email)

        val categoryId = readJson(
            mockMvc.post("/api/admin/categories") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = json("name" to unique("Cat"))
            }.andExpect { responseCode("00") }.andReturn(),
        )["categoryId"].asInt()
        val productId = readJson(
            mockMvc.post("/api/admin/products") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = json(
                    "name" to unique("Phone"),
                    "sku" to unique("sku"),
                    "categoryId" to categoryId,
                    "price" to 1000,
                    "stockQuantity" to 10,
                )
            }.andExpect { responseCode("00") }.andReturn(),
        )["productId"].asLong()
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
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to productId, "quantity" to 2)
        }.andExpect { responseCode("00") }

        val key = UUID.randomUUID().toString()
        fun checkout() = mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", key)
        }

        // Fail the last write of checkout. The cart was filled before this, so only checkout is affected.
        Mockito.doThrow(IllegalStateException("Simulated failure at the end of checkout"))
            .`when`(cartRepositorySpy).touch(anyLong())

        checkout().andExpect {
            responseCode("22")
            jsonPath("$.responseMessage") { value("An unexpected error occurred") }
        }

        // Everything checkout had already written was rolled back.
        assertEquals(0, orders(customerId))
        assertEquals(0, orderItems(customerId))
        assertEquals(0, historyRows(customerId))
        mockMvc.get("/api/customer/cart") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].quantity") { value(2) }
        }

        // Back to normal: the same key was not used up by the failed attempt.
        Mockito.reset(cartRepositorySpy)
        checkout().andExpect { responseCode("00"); jsonPath("$.responseMessage") { value("Order created") } }
        assertEquals(1, orders(customerId))
        assertEquals(1, orderItems(customerId))
        assertEquals(1, historyRows(customerId))
    }
}
