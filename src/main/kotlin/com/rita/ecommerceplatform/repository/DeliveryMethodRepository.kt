package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.DeliveryMethod
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class DeliveryMethodRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        DeliveryMethod(
            deliveryMethodId = rs.getInt("deliverymethodid"),
            code = rs.getString("deliverymethodcode"),
            name = rs.getString("deliverymethodname"),
            fee = rs.getBigDecimal("deliverymethodfee"),
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
}
