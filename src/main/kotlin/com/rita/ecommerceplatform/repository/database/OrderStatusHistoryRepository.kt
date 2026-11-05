package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.OrderStatusHistoryQuery
import org.jdbi.v3.core.Jdbi
import com.rita.ecommerceplatform.model.entity.OrderStatus
import com.rita.ecommerceplatform.model.entity.OrderStatusChange
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** ec_order_status_history is an append-only audit of every order status change: rows are inserted, never updated. */
interface OrderStatusHistoryRepository {
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
    )

    /** The order's status changes, oldest first (uses ix__ec_orderstatushistory__orderid_createdat). */
    fun findByOrderId(orderId: Long): List<OrderStatusChange>
}

@Repository
class OrderStatusHistoryRepositoryImpl(private val jdbi: Jdbi) : OrderStatusHistoryRepository {

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

    override fun insert(
        orderId: Long,
        fromStatus: OrderStatus?,
        toStatus: OrderStatus,
        adminId: Int?,
        customerId: Long?,
        note: String?,
    ) {
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(OrderStatusHistoryQuery.INSERT)
                .bind("orderId", orderId)
                .bind("fromStatus", fromStatus?.name)
                .bind("toStatus", toStatus.name)
                .bind("adminId", adminId)
                .bind("customerId", customerId)
                .bind("note", note)
                .execute()
        }
    }

    override fun findByOrderId(orderId: Long): List<OrderStatusChange> =
        jdbi.withHandle<List<OrderStatusChange>, Exception> { handle ->
            handle.createQuery(OrderStatusHistoryQuery.FIND_BY_ORDER_ID)
                .bind("orderId", orderId)
                .map(rowMapper)
                .list()
        }
}
