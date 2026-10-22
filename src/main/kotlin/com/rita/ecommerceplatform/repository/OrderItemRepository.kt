package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.OrderItem
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/** ec_order_item rows are a snapshot taken at checkout: inserted once, never updated. */
@Repository
class OrderItemRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        OrderItem(
            orderItemId = rs.getLong("orderitemid"),
            orderId = rs.getLong("orderitemorderid"),
            productId = rs.getLong("orderitemproductid"),
            productName = rs.getString("orderitemproductname"),
            productSku = rs.getString("orderitemproductsku"),
            unitPrice = rs.getBigDecimal("orderitemunitprice"),
            quantity = rs.getInt("orderitemquantity"),
            lineTotal = rs.getBigDecimal("orderitemlinetotal"),
            createdAt = rs.getObject("orderitemcreatedat", LocalDateTime::class.java),
        )
    }

    /** Inserts one order line. orderitemlinetotal is computed by the database (unit price x quantity). */
    fun insert(orderId: Long, productId: Long, productName: String, productSku: String, unitPrice: BigDecimal, quantity: Int) {
        jdbcClient.sql(
            """
            INSERT INTO public.ec_order_item (
                orderitemorderid, orderitemproductid, orderitemproductname, orderitemproductsku,
                orderitemunitprice, orderitemquantity
            )
            VALUES (:orderId, :productId, :productName, :productSku, :unitPrice, :quantity)
            """
        )
            .param("orderId", orderId)
            .param("productId", productId)
            .param("productName", productName)
            .param("productSku", productSku)
            .param("unitPrice", unitPrice)
            .param("quantity", quantity)
            .update()
    }

    fun findByOrderId(orderId: Long): List<OrderItem> =
        jdbcClient.sql("SELECT * FROM public.ec_order_item WHERE orderitemorderid = :orderId ORDER BY orderitemid")
            .param("orderId", orderId)
            .query(rowMapper)
            .list()

    /** Total quantity per order, for a whole page of orders in one query (H1). Orders without items are absent. */
    fun quantitiesByOrderIds(orderIds: Collection<Long>): Map<Long, Int> {
        if (orderIds.isEmpty()) return emptyMap()
        return jdbcClient.sql(
            """
            SELECT orderitemorderid, SUM(orderitemquantity) AS quantity
            FROM public.ec_order_item
            WHERE orderitemorderid IN (:orderIds)
            GROUP BY orderitemorderid
            """
        )
            .param("orderIds", orderIds)
            .query { rs, _ -> rs.getLong("orderitemorderid") to rs.getInt("quantity") }
            .list()
            .toMap()
    }
}
