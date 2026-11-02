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
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/** Coupon management for admins (checkpoint 3.1, Step 1). */
class CouponTest : IntegrationTestBase() {

    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
    }

    // Each test uses its own prefix, so list filters only see that test's coupons.
    private fun uniqueCode(prefix: String = "C") = "${prefix}_${UUID.randomUUID().toString().take(8).uppercase()}"

    private fun platformNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.ofHours(1))

    private fun create(body: String, token: String = adminToken): ResultActionsDsl =
        mockMvc.post("/api/admin/coupons") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    /** Creates a 10% coupon (plus any extra fields) and returns the response. */
    private fun createCoupon(code: String = uniqueCode(), vararg extra: Pair<String, Any?>): JsonNode =
        readJson(
            create(json("code" to code, "discountType" to "PERCENTAGE", "discountValue" to 10, *extra))
                .andExpect { status { isCreated() } }.andReturn()
        )

    private fun update(id: Int, body: String): ResultActionsDsl =
        mockMvc.patch("/api/admin/coupons/$id") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun setStatus(id: Int, status: String): ResultActionsDsl =
        mockMvc.patch("/api/admin/coupons/$id/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to status)
        }

    private fun get(id: Int): ResultActionsDsl =
        mockMvc.get("/api/admin/coupons/$id") { header("Authorization", "Bearer $adminToken") }

    private fun stateOf(id: Int): String = readJson(get(id).andExpect { status { isOk() } }.andReturn())["state"].asString()

    private fun listCodes(vararg params: Pair<String, String>): JsonNode =
        readJson(
            mockMvc.get("/api/admin/coupons") {
                header("Authorization", "Bearer $adminToken")
                params.forEach { (name, value) -> param(name, value) }
            }.andExpect { status { isOk() } }.andReturn()
        )

    private fun codes(page: JsonNode): List<String> = page["content"].toList().map { it["code"].asString() }

    private fun setColumn(id: Int, column: String, value: Any) {
        jdbcClient.sql("UPDATE public.ec_coupon SET $column = :value WHERE couponid = :id")
            .param("value", value).param("id", id).update()
    }

    @Test
    fun `an admin creates percentage and fixed coupons, stored in uppercase and usable at once`() {
        val code = uniqueCode().lowercase()
        create(
            json(
                "code" to "  $code  ",
                "description" to "10% off",
                "discountType" to "percentage",
                "discountValue" to 10,
                "maxDiscountAmount" to 5000,
                "minimumOrderValue" to 20000,
                "usageLimit" to 100,
                "expiresAt" to platformNow().plusDays(30).withNano(0).toString(),
            )
        ).andExpect {
            status { isCreated() }
            jsonPath("$.code") { value(code.uppercase()) }
            jsonPath("$.discountType") { value("PERCENTAGE") }
            jsonPath("$.discountValue") { value(10.0) }
            jsonPath("$.maxDiscountAmount") { value(5000.0) }
            jsonPath("$.minimumOrderValue") { value(20000.0) }
            jsonPath("$.usageLimit") { value(100) }
            jsonPath("$.usageCount") { value(0) }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.state") { value("USABLE") }
            jsonPath("$.createdByAdminId") { value(seedAdminId()) }
        }

        create(json("code" to uniqueCode(), "discountType" to "FIXED_AMOUNT", "discountValue" to 1500)).andExpect {
            status { isCreated() }
            jsonPath("$.discountType") { value("FIXED_AMOUNT") }
            jsonPath("$.minimumOrderValue") { value(0.0) }
            jsonPath("$.usageLimit") { doesNotExist() }
            jsonPath("$.expiresAt") { doesNotExist() }
            jsonPath("$.state") { value("USABLE") }
        }
    }

    @Test
    fun `a code already used, in any case or by a deleted coupon, is refused`() {
        val code = uniqueCode()
        val couponId = createCoupon(code)["couponId"].asInt()

        create(json("code" to code.lowercase(), "discountType" to "PERCENTAGE", "discountValue" to 5)).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("Coupon code is already in use") }
        }

        mockMvc.delete("/api/admin/coupons/$couponId") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isNoContent() } }
        create(json("code" to code, "discountType" to "PERCENTAGE", "discountValue" to 5))
            .andExpect { status { isConflict() } }
    }

    @Test
    fun `invalid coupons are rejected with a clear reason`() {
        fun expectBadRequest(vararg fields: Pair<String, Any?>, message: String? = null) {
            val body = mapOf<String, Any?>("code" to uniqueCode(), "discountType" to "PERCENTAGE", "discountValue" to 10) + fields
            create(json(*body.toList().toTypedArray())).andExpect {
                status { isBadRequest() }
                message?.let { jsonPath("$.message") { value(it) } }
            }
        }

        expectBadRequest("code" to "A!")
        expectBadRequest("code" to "AB")
        expectBadRequest("discountType" to "HALF_OFF")
        expectBadRequest("discountValue" to 0)
        expectBadRequest("discountValue" to -5)
        expectBadRequest("minimumOrderValue" to -1)
        expectBadRequest("usageLimit" to 0)
        expectBadRequest("discountValue" to 101, message = "discountValue must not be more than 100 for a PERCENTAGE coupon")
        expectBadRequest(
            "discountType" to "FIXED_AMOUNT", "discountValue" to 500, "maxDiscountAmount" to 100,
            message = "maxDiscountAmount is only for PERCENTAGE coupons",
        )
        expectBadRequest("expiresAt" to platformNow().minusMinutes(1).withNano(0).toString(), message = "expiresAt must be in the future")
        expectBadRequest(
            "startsAt" to platformNow().plusDays(5).withNano(0).toString(),
            "expiresAt" to platformNow().plusDays(2).withNano(0).toString(),
            message = "expiresAt must be after startsAt",
        )
        // A fixed amount over 100 is fine.
        create(json("code" to uniqueCode(), "discountType" to "FIXED_AMOUNT", "discountValue" to 2500))
            .andExpect { status { isCreated() } }
    }

    @Test
    fun `PATCH changes only what was sent, checks the merged result, and the code never changes`() {
        val coupon = createCoupon(uniqueCode(), "maxDiscountAmount" to 3000, "usageLimit" to 50, "description" to "Old")
        val id = coupon["couponId"].asInt()

        update(id, json("discountValue" to 15, "description" to "")).andExpect {
            status { isOk() }
            jsonPath("$.discountValue") { value(15.0) }
            jsonPath("$.description") { doesNotExist() }
            jsonPath("$.maxDiscountAmount") { value(3000.0) }
            jsonPath("$.usageLimit") { value(50) }
            jsonPath("$.code") { value(coupon["code"].asString()) }
        }

        // FIXED_AMOUNT with the stored maximum is refused; clearing it at the same time works.
        update(id, json("discountType" to "FIXED_AMOUNT")).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("maxDiscountAmount is only for PERCENTAGE coupons") }
        }
        update(id, json("discountType" to "FIXED_AMOUNT", "discountValue" to 2000, "clearMaxDiscountAmount" to true)).andExpect {
            status { isOk() }
            jsonPath("$.discountType") { value("FIXED_AMOUNT") }
            jsonPath("$.maxDiscountAmount") { doesNotExist() }
        }
        // Back to PERCENTAGE with the stored 2000 is over 100.
        update(id, json("discountType" to "PERCENTAGE")).andExpect { status { isBadRequest() } }

        update(id, json("clearUsageLimit" to true)).andExpect { jsonPath("$.usageLimit") { doesNotExist() } }
        update(id, json("code" to "NEWCODE")).andExpect { status { isBadRequest() } }
        update(id, json("status" to "INACTIVE")).andExpect { status { isBadRequest() } }
        update(id, json()).andExpect { status { isBadRequest() } }
        update(id, json("usageLimit" to 5, "clearUsageLimit" to true)).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `the usage limit cannot go below the usage count`() {
        val id = createCoupon(uniqueCode(), "usageLimit" to 10)["couponId"].asInt()
        setColumn(id, "couponusagecount", 4)

        update(id, json("usageLimit" to 3)).andExpect {
            status { isConflict() }
            jsonPath("$.message") { value("Already used 4 times, so usageLimit cannot be lower than that") }
        }
        update(id, json("usageLimit" to 4)).andExpect {
            status { isOk() }
            jsonPath("$.usageLimit") { value(4) }
            jsonPath("$.usageCount") { value(4) }
            jsonPath("$.state") { value("EXHAUSTED") }
        }
    }

    @Test
    fun `state shows whether the coupon works now, and why not`() {
        val scheduled = createCoupon(uniqueCode(), "startsAt" to platformNow().plusDays(1).withNano(0).toString())
        assertEquals("SCHEDULED", scheduled["state"].asString())

        val expired = createCoupon()["couponId"].asInt()
        setColumn(expired, "couponexpiresat", platformNow().minusMinutes(1))
        assertEquals("EXPIRED", stateOf(expired))

        val exhausted = createCoupon(uniqueCode(), "usageLimit" to 2)["couponId"].asInt()
        setColumn(exhausted, "couponusagecount", 2)
        assertEquals("EXHAUSTED", stateOf(exhausted))

        val inactive = createCoupon()["couponId"].asInt()
        setStatus(inactive, "INACTIVE").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("INACTIVE") }
            jsonPath("$.state") { value("INACTIVE") }
        }
        setStatus(inactive, "active").andExpect { jsonPath("$.state") { value("USABLE") } }
        setStatus(inactive, "DELETED").andExpect { status { isBadRequest() } }

        // An expired coupon can still have other fields edited; only a newly sent expiry must be in the future.
        update(expired, json("description" to "Ended")).andExpect { status { isOk() } }
        update(expired, json("expiresAt" to platformNow().plusDays(1).withNano(0).toString())).andExpect {
            jsonPath("$.state") { value("USABLE") }
        }
    }

    @Test
    fun `the list is newest first, filters by status and code prefix, and pages`() {
        val prefix = "L${UUID.randomUUID().toString().take(6).uppercase()}"
        createCoupon("${prefix}_A")
        val second = createCoupon("${prefix}_B")
        createCoupon("${prefix}_C")
        setStatus(second["couponId"].asInt(), "INACTIVE")

        assertEquals(listOf("${prefix}_C", "${prefix}_B", "${prefix}_A"), codes(listCodes("code" to prefix.lowercase())))
        assertEquals(listOf("${prefix}_B"), codes(listCodes("code" to prefix, "status" to "inactive")))
        assertEquals(listOf("${prefix}_C", "${prefix}_A"), codes(listCodes("code" to prefix, "status" to "ACTIVE")))

        val page = listCodes("code" to prefix, "size" to "2", "page" to "1")
        assertEquals(listOf("${prefix}_A"), codes(page))
        assertEquals(3, page["totalElements"].asInt())
        assertEquals(2, page["totalPages"].asInt())

        // The text is matched literally: % is not a wildcard.
        assertEquals(emptyList(), codes(listCodes("code" to "${prefix}%")))
        mockMvc.get("/api/admin/coupons") {
            header("Authorization", "Bearer $adminToken")
            param("status", "DELETED")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a deleted coupon leaves the list and is 404 from then on`() {
        val code = uniqueCode("D")
        val id = createCoupon(code)["couponId"].asInt()

        mockMvc.delete("/api/admin/coupons/$id") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isNoContent() } }

        assertEquals(emptyList(), codes(listCodes("code" to code)))
        get(id).andExpect {
            status { isNotFound() }
            jsonPath("$.message") { value("Coupon not found") }
        }
        update(id, json("discountValue" to 20)).andExpect { status { isNotFound() } }
        setStatus(id, "ACTIVE").andExpect { status { isNotFound() } }
        mockMvc.delete("/api/admin/coupons/$id") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isNotFound() } }
        get(999999).andExpect { status { isNotFound() } }

        // The row stays, so orders that used it can still point to it.
        val status = jdbcClient.sql("SELECT couponstatus FROM public.ec_coupon WHERE couponid = :id")
            .param("id", id).query(String::class.java).single()
        assertEquals("DELETED", status)
    }

    @Test
    fun `customers, anonymous callers and admins who must change their password are refused`() {
        val customerToken = newCustomerToken()
        val body = json("code" to uniqueCode(), "discountType" to "PERCENTAGE", "discountValue" to 10)

        create(body, token = customerToken).andExpect { status { isForbidden() } }
        mockMvc.get("/api/admin/coupons") { header("Authorization", "Bearer $customerToken") }
            .andExpect { status { isForbidden() } }
        mockMvc.post("/api/admin/coupons") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/admin/coupons") { header("Authorization", "Bearer ${loginSeedAdmin()}") }
            .andExpect { status { isForbidden() } }
    }
}
