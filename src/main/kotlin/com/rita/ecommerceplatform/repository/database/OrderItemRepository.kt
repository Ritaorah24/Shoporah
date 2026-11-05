package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.OrderItemQuery
import org.jdbi.v3.core.Jdbi
import com.rita.ecommerceplatform.model.entity.OrderItem
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/** ec_order_item rows are a snapshot taken at checkout: inserted once, never updated. */
interface OrderItemRepository {
    /** Inserts one order line. orderitemlinetotal is computed by the database (unit price x quantity). */
    fun insert(orderId: Long, productId: Long, productName: String, productSku: String, unitPrice: BigDecimal, quantity: Int)

    fun findByOrderId(orderId: Long): List<OrderItem>

    /** Total quantity per order, for a whole page of orders in one query (H1). Orders without items are absent. */
    fun quantitiesByOrderIds(orderIds: Collection<Long>): Map<Long, Int>
}

@Repository
class OrderItemRepositoryImpl(private val jdbi: Jdbi) : OrderItemRepository {

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

    override fun insert(orderId: Long, productId: Long, productName: String, productSku: String, unitPrice: BigDecimal, quantity: Int) {
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(OrderItemQuery.INSERT)
                .bind("orderId", orderId)
                .bind("productId", productId)
                .bind("productName", productName)
                .bind("productSku", productSku)
                .bind("unitPrice", unitPrice)
                .bind("quantity", quantity)
                .execute()
        }
    }

    override fun findByOrderId(orderId: Long): List<OrderItem> =
        jdbi.withHandle<List<OrderItem>, Exception> { handle ->
            handle.createQuery(OrderItemQuery.FIND_BY_ORDER_ID)
                .bind("orderId", orderId)
                .map(rowMapper)
                .list()
        }

    override fun quantitiesByOrderIds(orderIds: Collection<Long>): Map<Long, Int> {
        if (orderIds.isEmpty()) return emptyMap()
        return jdbi.withHandle<Map<Long, Int>, Exception> { handle ->
            handle.createQuery(OrderItemQuery.QUANTITIES_BY_ORDER_IDS)
                .bindList("orderIds", orderIds)
            .map(RowMapper { rs, _ -> rs.getLong("orderitemorderid") to rs.getInt("quantity") })
            .list()
            .toMap()
        }
    }
}
