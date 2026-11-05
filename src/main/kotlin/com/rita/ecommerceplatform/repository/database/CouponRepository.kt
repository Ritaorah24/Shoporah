package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.CouponQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.startsWithPattern
import com.rita.ecommerceplatform.model.entity.Coupon
import com.rita.ecommerceplatform.model.entity.CouponDiscountType
import com.rita.ecommerceplatform.model.entity.CouponStatus
import org.jdbi.v3.core.mapper.RowMapper
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

interface CouponRepository {
    fun findById(couponId: Int): Coupon?

    /** Locks the row until the transaction ends, so an edit of the usage limit cannot race a redemption. */
    fun lockById(couponId: Int): Coupon?

    /** By code, any status, case-insensitive (CITEXT). The checkout preview reads it without a lock. */
    fun findByCode(code: String): Coupon?

    /** Like findByCode, but locks the row until the transaction ends, so two checkouts cannot both take the last use. */
    fun lockByCode(code: String): Coupon?

    /**
     * Counts one use, only while the coupon is ACTIVE and below its limit. Returns the rows updated (0 or 1);
     * the caller holds the row lock and checks for 1. ck__ec_coupon__usagecount is the last guard.
     */
    fun redeem(couponId: Int): Int

    /** Gives one use back (an unpaid order was cancelled). Any status: a deleted coupon's count is corrected too. */
    fun release(couponId: Int): Int

    /** Any status, DELETED included: a deleted coupon's code stays taken (uq__ec_coupon__code, case-insensitive). */
    fun existsByCode(code: String): Boolean

    /** Admin list, newest first. Never DELETED; `statuses` narrows it further. */
    fun search(statuses: List<CouponStatus>, codeStartsWith: String?, limit: Int, offset: Long): List<Coupon>

    fun count(statuses: List<CouponStatus>, codeStartsWith: String?): Long

    /**
     * Inserts an ACTIVE coupon and returns its id. Throws DuplicateKeyException if the code is taken
     * (uq__ec_coupon__code, case-insensitive): the service turns that into 409.
     */
    fun insert(code: String, terms: CouponTerms, createdByAdminId: Int): Int

    /** Sets every editable field (the service merges what was sent with the current values). The code never changes. */
    fun update(couponId: Int, terms: CouponTerms): Int

    fun updateStatus(couponId: Int, status: CouponStatus): Int
}

@Repository
class CouponRepositoryImpl(private val jdbi: Jdbi) : CouponRepository {

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

    override fun findById(couponId: Int): Coupon? =
        jdbi.withHandle<Coupon?, Exception> { handle ->
            handle.createQuery(CouponQuery.FIND_BY_ID)
                .bind("id", couponId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun lockById(couponId: Int): Coupon? =
        jdbi.withHandle<Coupon?, Exception> { handle ->
            handle.createQuery(CouponQuery.LOCK_BY_ID)
                .bind("id", couponId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun findByCode(code: String): Coupon? =
        jdbi.withHandle<Coupon?, Exception> { handle ->
            handle.createQuery(CouponQuery.FIND_BY_CODE)
                .bind("code", code)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun lockByCode(code: String): Coupon? =
        jdbi.withHandle<Coupon?, Exception> { handle ->
            handle.createQuery(CouponQuery.LOCK_BY_CODE)
                .bind("code", code)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun redeem(couponId: Int): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CouponQuery.REDEEM)
                .bind("id", couponId)
                .execute()
        }

    override fun release(couponId: Int): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CouponQuery.RELEASE)
                .bind("id", couponId)
                .execute()
        }

    override fun existsByCode(code: String): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(CouponQuery.EXISTS_BY_CODE)
                .bind("code", code)
                .mapTo<Boolean>()
                .one()
        }

    override fun search(statuses: List<CouponStatus>, codeStartsWith: String?, limit: Int, offset: Long): List<Coupon> {
        val (where, params) = searchWhere(statuses, codeStartsWith)
        return jdbi.withHandle<List<Coupon>, Exception> { handle ->
            handle.createQuery("""
            SELECT * FROM ${PrefixConstant.ENTITY}COUPON
            $where
            ORDER BY couponcreatedat DESC, couponid DESC
            LIMIT :limit OFFSET :offset
            """)
                .bindList("statuses", statuses.map { it.name })
                .bindMap(params + mapOf("limit" to limit, "offset" to offset))
                .map(rowMapper)
                .list()
        }
    }

    override fun count(statuses: List<CouponStatus>, codeStartsWith: String?): Long {
        val (where, params) = searchWhere(statuses, codeStartsWith)
        return jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM ${PrefixConstant.ENTITY}COUPON $where")
                .bindList("statuses", statuses.map { it.name })
                .bindMap(params)
                .mapTo<Long>()
                .one()
        }
    }

    // Fixed SQL fragments only; the values are named parameters.
    private fun searchWhere(statuses: List<CouponStatus>, codeStartsWith: String?): Pair<String, Map<String, Any>> {
        val conditions = mutableListOf("couponstatus IN (<statuses>)")
        val params = mutableMapOf<String, Any>()
        codeStartsWith?.let {
            conditions += "(couponcode::TEXT) ILIKE :pattern ESCAPE '!'"
            params["pattern"] = startsWithPattern(it)
        }
        return "WHERE " + conditions.joinToString(" AND ") to params
    }

    override fun insert(code: String, terms: CouponTerms, createdByAdminId: Int): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery(CouponQuery.INSERT)
                .bind("code", code)
                .bindMap(termParams(terms))
                .bind("adminId", createdByAdminId)
                .mapTo<Int>()
                .one()
        }

    override fun update(couponId: Int, terms: CouponTerms): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CouponQuery.UPDATE)
                .bindMap(termParams(terms))
                .bind("id", couponId)
                .execute()
        }

    override fun updateStatus(couponId: Int, status: CouponStatus): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CouponQuery.UPDATE_STATUS)
                .bind("status", status.name)
                .bind("id", couponId)
                .execute()
        }

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
