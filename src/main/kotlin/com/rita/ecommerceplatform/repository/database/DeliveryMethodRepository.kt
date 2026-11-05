package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.DeliveryMethodQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.DeliveryMethod
import com.rita.ecommerceplatform.model.entity.DeliveryMethodStatus
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

interface DeliveryMethodRepository {
    /** An ACTIVE delivery method by code (case-insensitive: CITEXT), e.g. the seeded "STANDARD" (V8). */
    fun findActiveByCode(code: String): DeliveryMethod?

    fun findById(deliveryMethodId: Int): DeliveryMethod?

    /** Every method, active and inactive, by name (admin list). */
    fun findAll(): List<DeliveryMethod>

    /** Active methods, cheapest first (public list). */
    fun findAllActive(): List<DeliveryMethod>

    fun existsByCode(code: String): Boolean

    /**
     * Inserts an ACTIVE method and returns its id. Throws DuplicateKeyException if the code is taken
     * (uq__ec_deliverymethod__code, case-insensitive): the service turns that into 409.
     */
    fun insert(
        code: String,
        name: String,
        description: String?,
        fee: BigDecimal,
        estimatedMinDays: Int?,
        estimatedMaxDays: Int?,
    ): Int

    /** Sets every editable field (the service merges what was sent with the current values). The code never changes. */
    fun update(
        deliveryMethodId: Int,
        name: String,
        description: String?,
        fee: BigDecimal,
        estimatedMinDays: Int?,
        estimatedMaxDays: Int?,
    ): Int

    fun updateStatus(deliveryMethodId: Int, status: DeliveryMethodStatus): Int
}

@Repository
class DeliveryMethodRepositoryImpl(private val jdbi: Jdbi) : DeliveryMethodRepository {

    private val rowMapper = RowMapper { rs, _ ->
        DeliveryMethod(
            deliveryMethodId = rs.getInt("deliverymethodid"),
            code = rs.getString("deliverymethodcode"),
            name = rs.getString("deliverymethodname"),
            description = rs.getString("deliverymethoddescription"),
            fee = rs.getBigDecimal("deliverymethodfee"),
            estimatedMinDays = rs.getObject("deliverymethodestimatedmindays", Int::class.javaObjectType),
            estimatedMaxDays = rs.getObject("deliverymethodestimatedmaxdays", Int::class.javaObjectType),
            status = DeliveryMethodStatus.valueOf(rs.getString("deliverymethodstatus").uppercase()),
            createdAt = rs.getObject("deliverymethodcreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("deliverymethodupdatedat", LocalDateTime::class.java),
        )
    }

    override fun findActiveByCode(code: String): DeliveryMethod? =
        jdbi.withHandle<DeliveryMethod?, Exception> { handle ->
            handle.createQuery(DeliveryMethodQuery.FIND_ACTIVE_BY_CODE)
                .bind("code", code)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun findById(deliveryMethodId: Int): DeliveryMethod? =
        jdbi.withHandle<DeliveryMethod?, Exception> { handle ->
            handle.createQuery(DeliveryMethodQuery.FIND_BY_ID)
                .bind("id", deliveryMethodId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun findAll(): List<DeliveryMethod> =
        jdbi.withHandle<List<DeliveryMethod>, Exception> { handle ->
            handle.createQuery(DeliveryMethodQuery.FIND_ALL)
                .map(rowMapper)
                .list()
        }

    override fun findAllActive(): List<DeliveryMethod> =
        jdbi.withHandle<List<DeliveryMethod>, Exception> { handle ->
            handle.createQuery(DeliveryMethodQuery.FIND_ALL_ACTIVE)
                .map(rowMapper)
                .list()
        }

    override fun existsByCode(code: String): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(DeliveryMethodQuery.EXISTS_BY_CODE)
                .bind("code", code)
                .mapTo<Boolean>()
                .one()
        }

    override fun insert(
        code: String,
        name: String,
        description: String?,
        fee: BigDecimal,
        estimatedMinDays: Int?,
        estimatedMaxDays: Int?,
    ): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery(DeliveryMethodQuery.INSERT)
                .bind("code", code)
                .bind("name", name)
                .bind("description", description)
                .bind("fee", fee)
                .bind("minDays", estimatedMinDays)
                .bind("maxDays", estimatedMaxDays)
                .mapTo<Int>()
                .one()
        }

    override fun update(
        deliveryMethodId: Int,
        name: String,
        description: String?,
        fee: BigDecimal,
        estimatedMinDays: Int?,
        estimatedMaxDays: Int?,
    ): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(DeliveryMethodQuery.UPDATE)
                .bind("name", name)
                .bind("description", description)
                .bind("fee", fee)
                .bind("minDays", estimatedMinDays)
                .bind("maxDays", estimatedMaxDays)
                .bind("id", deliveryMethodId)
                .execute()
        }

    override fun updateStatus(deliveryMethodId: Int, status: DeliveryMethodStatus): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(DeliveryMethodQuery.UPDATE_STATUS)
                .bind("status", status.name)
                .bind("id", deliveryMethodId)
                .execute()
        }
}
