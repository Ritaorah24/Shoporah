package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.Coupon
import com.rita.ecommerceplatform.model.CouponDiscountType
import com.rita.ecommerceplatform.model.CouponStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/** The coupon fields an admin sets; the code, creator and usage count are not part of it. */
data class CouponTerms(
    val description: String?,
    val discountType: CouponDiscountType,
    val discountValue: BigDecimal,
    val maxDiscountAmount: BigDecimal?,
    val minimumOrderValue: BigDecimal,
    val usageLimit: Int?,
    val startsAt: LocalDateTime?,
    val expiresAt: LocalDateTime?,
)

@Repository
class CouponRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        Coupon(
            couponId = rs.getInt("couponid"),
            code = rs.getString("couponcode"),
            description = rs.getString("coupondescription"),
            discountType = CouponDiscountType.valueOf(rs.getString("coupondiscounttype").uppercase()),
            discountValue = rs.getBigDecimal("coupondiscountvalue"),
            maxDiscountAmount = rs.getBigDecimal("couponmaxdiscountamount"),
            minimumOrderValue = rs.getBigDecimal("couponminimumordervalue"),
            usageLimit = rs.getObject("couponusagelimit", Int::class.javaObjectType),
            usageCount = rs.getInt("couponusagecount"),
            startsAt = rs.getObject("couponstartsat", LocalDateTime::class.java),
            expiresAt = rs.getObject("couponexpiresat", LocalDateTime::class.java),
            createdByAdminId = rs.getInt("couponcreatedbyadminid"),
            status = CouponStatus.valueOf(rs.getString("couponstatus").uppercase()),
            createdAt = rs.getObject("couponcreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("couponupdatedat", LocalDateTime::class.java),
        )
    }

    fun findById(couponId: Int): Coupon? =
        jdbcClient.sql("SELECT * FROM public.ec_coupon WHERE couponid = :id")
            .param("id", couponId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** Locks the row until the transaction ends, so an edit of the usage limit cannot race a redemption. */
    fun lockById(couponId: Int): Coupon? =
        jdbcClient.sql("SELECT * FROM public.ec_coupon WHERE couponid = :id FOR UPDATE")
            .param("id", couponId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** By code, any status, case-insensitive (CITEXT). The checkout preview reads it without a lock. */
    fun findByCode(code: String): Coupon? =
        jdbcClient.sql("SELECT * FROM public.ec_coupon WHERE couponcode = :code")
            .param("code", code)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** Like findByCode, but locks the row until the transaction ends, so two checkouts cannot both take the last use. */
    fun lockByCode(code: String): Coupon? =
        jdbcClient.sql("SELECT * FROM public.ec_coupon WHERE couponcode = :code FOR UPDATE")
            .param("code", code)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /**
     * Counts one use, only while the coupon is ACTIVE and below its limit. Returns the rows updated (0 or 1);
     * the caller holds the row lock and checks for 1. ck__ec_coupon__usagecount is the last guard.
     */
    fun redeem(couponId: Int): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_coupon
            SET couponusagecount = couponusagecount + 1,
                couponupdatedat = $SQL_NOW
            WHERE couponid = :id
              AND couponstatus = 'ACTIVE'
              AND (couponusagelimit IS NULL OR couponusagecount < couponusagelimit)
            """
        )
            .param("id", couponId)
            .update()

    /** Gives one use back (an unpaid order was cancelled). Any status: a deleted coupon's count is corrected too. */
    fun release(couponId: Int): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_coupon
            SET couponusagecount = couponusagecount - 1,
                couponupdatedat = $SQL_NOW
            WHERE couponid = :id AND couponusagecount > 0
            """
        )
            .param("id", couponId)
            .update()

    /** Any status, DELETED included: a deleted coupon's code stays taken (uq__ec_coupon__code, case-insensitive). */
    fun existsByCode(code: String): Boolean =
        jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM public.ec_coupon WHERE couponcode = :code)")
            .param("code", code)
            .query(Boolean::class.javaObjectType)
            .single()

    /** Admin list, newest first. Never DELETED; `statuses` narrows it further. */
    fun search(statuses: List<CouponStatus>, codeStartsWith: String?, limit: Int, offset: Long): List<Coupon> {
        val (where, params) = searchWhere(statuses, codeStartsWith)
        return jdbcClient.sql(
            """
            SELECT * FROM public.ec_coupon
            $where
            ORDER BY couponcreatedat DESC, couponid DESC
            LIMIT :limit OFFSET :offset
            """
        )
            .params(params + mapOf("limit" to limit, "offset" to offset))
            .query(rowMapper)
            .list()
    }

    fun count(statuses: List<CouponStatus>, codeStartsWith: String?): Long {
        val (where, params) = searchWhere(statuses, codeStartsWith)
        return jdbcClient.sql("SELECT COUNT(*) FROM public.ec_coupon $where")
            .params(params)
            .query(Long::class.javaObjectType)
            .single()
    }

    // Fixed SQL fragments only; the values are named parameters.
    private fun searchWhere(statuses: List<CouponStatus>, codeStartsWith: String?): Pair<String, Map<String, Any>> {
        val conditions = mutableListOf("couponstatus IN (:statuses)")
        val params = mutableMapOf<String, Any>("statuses" to statuses.map { it.name })
        codeStartsWith?.let {
            conditions += "(couponcode::TEXT) ILIKE :pattern ESCAPE '\\'"
            params["pattern"] = startsWithPattern(it)
        }
        return "WHERE " + conditions.joinToString(" AND ") to params
    }

    /**
     * Inserts an ACTIVE coupon and returns its id. Throws DuplicateKeyException if the code is taken
     * (uq__ec_coupon__code, case-insensitive): the service turns that into 409.
     */
    fun insert(code: String, terms: CouponTerms, createdByAdminId: Int): Int =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_coupon (
                couponcode, coupondescription, coupondiscounttype, coupondiscountvalue, couponmaxdiscountamount,
                couponminimumordervalue, couponusagelimit, couponstartsat, couponexpiresat, couponcreatedbyadminid
            )
            VALUES (
                :code, :description, :discountType, :discountValue, :maxDiscountAmount,
                :minimumOrderValue, :usageLimit, :startsAt, :expiresAt, :adminId
            )
            RETURNING couponid
            """
        )
            .param("code", code)
            .params(termParams(terms))
            .param("adminId", createdByAdminId)
            .query(Int::class.javaObjectType)
            .single()

    /** Sets every editable field (the service merges what was sent with the current values). The code never changes. */
    fun update(couponId: Int, terms: CouponTerms): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_coupon
            SET coupondescription = :description,
                coupondiscounttype = :discountType,
                coupondiscountvalue = :discountValue,
                couponmaxdiscountamount = :maxDiscountAmount,
                couponminimumordervalue = :minimumOrderValue,
                couponusagelimit = :usageLimit,
                couponstartsat = :startsAt,
                couponexpiresat = :expiresAt,
                couponupdatedat = $SQL_NOW
            WHERE couponid = :id AND couponstatus <> 'DELETED'
            """
        )
            .params(termParams(terms))
            .param("id", couponId)
            .update()

    fun updateStatus(couponId: Int, status: CouponStatus): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_coupon
            SET couponstatus = :status,
                couponupdatedat = $SQL_NOW
            WHERE couponid = :id AND couponstatus <> 'DELETED'
            """
        )
            .param("status", status.name)
            .param("id", couponId)
            .update()

    private fun termParams(terms: CouponTerms): Map<String, Any?> = mapOf(
        "description" to terms.description,
        "discountType" to terms.discountType.name,
        "discountValue" to terms.discountValue,
        "maxDiscountAmount" to terms.maxDiscountAmount,
        "minimumOrderValue" to terms.minimumOrderValue,
        "usageLimit" to terms.usageLimit,
        "startsAt" to terms.startsAt,
        "expiresAt" to terms.expiresAt,
    )
}
