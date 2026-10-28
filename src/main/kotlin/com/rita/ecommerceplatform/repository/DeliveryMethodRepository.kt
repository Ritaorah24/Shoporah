package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.DeliveryMethod
import com.rita.ecommerceplatform.model.DeliveryMethodStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

@Repository
class DeliveryMethodRepository(private val jdbcClient: JdbcClient) {

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

    /** An ACTIVE delivery method by code (case-insensitive: CITEXT), e.g. the seeded "STANDARD" (V8). */
    fun findActiveByCode(code: String): DeliveryMethod? =
        jdbcClient.sql(
            "SELECT * FROM public.ec_delivery_method WHERE deliverymethodcode = :code AND deliverymethodstatus = 'ACTIVE'"
        )
            .param("code", code)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun findById(deliveryMethodId: Int): DeliveryMethod? =
        jdbcClient.sql("SELECT * FROM public.ec_delivery_method WHERE deliverymethodid = :id")
            .param("id", deliveryMethodId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** Every method, active and inactive, by name (admin list). */
    fun findAll(): List<DeliveryMethod> =
        jdbcClient.sql("SELECT * FROM public.ec_delivery_method ORDER BY deliverymethodname, deliverymethodid")
            .query(rowMapper)
            .list()

    /** Active methods, cheapest first (public list). */
    fun findAllActive(): List<DeliveryMethod> =
        jdbcClient.sql(
            """
            SELECT * FROM public.ec_delivery_method
            WHERE deliverymethodstatus = 'ACTIVE'
            ORDER BY deliverymethodfee, deliverymethodname, deliverymethodid
            """
        )
            .query(rowMapper)
            .list()

    fun existsByCode(code: String): Boolean =
        jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM public.ec_delivery_method WHERE deliverymethodcode = :code)")
            .param("code", code)
            .query(Boolean::class.javaObjectType)
            .single()

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
    ): Int =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_delivery_method (
                deliverymethodcode, deliverymethodname, deliverymethoddescription, deliverymethodfee,
                deliverymethodestimatedmindays, deliverymethodestimatedmaxdays
            )
            VALUES (:code, :name, :description, :fee, :minDays, :maxDays)
            RETURNING deliverymethodid
            """
        )
            .param("code", code)
            .param("name", name)
            .param("description", description)
            .param("fee", fee)
            .param("minDays", estimatedMinDays)
            .param("maxDays", estimatedMaxDays)
            .query(Int::class.javaObjectType)
            .single()

    /** Sets every editable field (the service merges what was sent with the current values). The code never changes. */
    fun update(
        deliveryMethodId: Int,
        name: String,
        description: String?,
        fee: BigDecimal,
        estimatedMinDays: Int?,
        estimatedMaxDays: Int?,
    ): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_delivery_method
            SET deliverymethodname = :name,
                deliverymethoddescription = :description,
                deliverymethodfee = :fee,
                deliverymethodestimatedmindays = :minDays,
                deliverymethodestimatedmaxdays = :maxDays,
                deliverymethodupdatedat = $SQL_NOW
            WHERE deliverymethodid = :id
            """
        )
            .param("name", name)
            .param("description", description)
            .param("fee", fee)
            .param("minDays", estimatedMinDays)
            .param("maxDays", estimatedMaxDays)
            .param("id", deliveryMethodId)
            .update()

    fun updateStatus(deliveryMethodId: Int, status: DeliveryMethodStatus): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_delivery_method
            SET deliverymethodstatus = :status,
                deliverymethodupdatedat = $SQL_NOW
            WHERE deliverymethodid = :id
            """
        )
            .param("status", status.name)
            .param("id", deliveryMethodId)
            .update()
}
