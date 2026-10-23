package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.OrderStatus
import com.rita.ecommerceplatform.model.OrderStatusChange
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** ec_order_status_history is an append-only audit of every order status change: rows are inserted, never updated. */
@Repository
class OrderStatusHistoryRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        OrderStatusChange(
            fromStatus = rs.getString("orderstatushistoryfromstatus")?.let { OrderStatus.valueOf(it.uppercase()) },
            toStatus = OrderStatus.valueOf(rs.getString("orderstatushistorytostatus").uppercase()),
            note = rs.getString("orderstatushistorynote"),
            createdAt = rs.getObject("orderstatushistorycreatedat", LocalDateTime::class.java),
            changedByAdminId = rs.getObject("orderstatushistorychangedbyadminid", Int::class.javaObjectType),
            changedByCustomerId = rs.getObject("orderstatushistorychangedbycustomerid", Long::class.javaObjectType),
        )
    }

    /**
     * Records one status change. fromStatus is null for the order's first status (PENDING at checkout).
     * At most one actor may be set (ck__ec_orderstatushistory__actor); neither means the system made the change.
     */
    fun insert(
        orderId: Long,
        fromStatus: OrderStatus?,
        toStatus: OrderStatus,
        adminId: Int?,
        customerId: Long?,
        note: String?,
    ) {
        jdbcClient.sql(
            """
            INSERT INTO public.ec_order_status_history (
                orderstatushistoryorderid, orderstatushistoryfromstatus, orderstatushistorytostatus,
                orderstatushistorychangedbyadminid, orderstatushistorychangedbycustomerid, orderstatushistorynote
            )
            VALUES (:orderId, :fromStatus, :toStatus, :adminId, :customerId, :note)
            """
        )
            .param("orderId", orderId)
            .param("fromStatus", fromStatus?.name)
            .param("toStatus", toStatus.name)
            .param("adminId", adminId)
            .param("customerId", customerId)
            .param("note", note)
            .update()
    }

    /** The order's status changes, oldest first (uses ix__ec_orderstatushistory__orderid_createdat). */
    fun findByOrderId(orderId: Long): List<OrderStatusChange> =
        jdbcClient.sql(
            """
            SELECT * FROM public.ec_order_status_history
            WHERE orderstatushistoryorderid = :orderId
            ORDER BY orderstatushistorycreatedat, orderstatushistoryid
            """
        )
            .param("orderId", orderId)
            .query(rowMapper)
            .list()
}
