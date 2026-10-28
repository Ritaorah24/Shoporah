package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.InventoryMovementType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** ec_inventory_movement is an append-only log of stock changes (D7): rows are inserted, never updated. */
@Repository
class InventoryMovementRepository(private val jdbcClient: JdbcClient) {

    /**
     * Records one stock change. quantityChange is signed (+ restock, - adjustment) and must not be 0
     * (ck__ec_inventorymovement__quantitychange); stockAfter is the product's stock after the change.
     */
    fun insert(
        productId: Long,
        type: InventoryMovementType,
        quantityChange: Int,
        stockAfter: Int,
        adminId: Int?,
        note: String?,
        orderId: Long? = null,
    ) {
        jdbcClient.sql(
            """
            INSERT INTO public.ec_inventory_movement (
                inventorymovementproductid, inventorymovementtype, inventorymovementquantitychange,
                inventorymovementstockafter, inventorymovementadminid, inventorymovementnote, inventorymovementorderid
            )
            VALUES (:productId, :type, :quantityChange, :stockAfter, :adminId, :note, :orderId)
            """
        )
            .param("productId", productId)
            .param("type", type.name)
            .param("quantityChange", quantityChange)
            .param("stockAfter", stockAfter)
            .param("adminId", adminId)
            .param("note", note)
            .param("orderId", orderId)
            .update()
    }

    /** What the order's payment took from stock (its SALE movements), by productId; empty if nothing was taken. */
    fun findSalesByOrder(orderId: Long): List<OrderSale> =
        jdbcClient.sql(
            """
            SELECT inventorymovementproductid, -inventorymovementquantitychange AS quantity
            FROM public.ec_inventory_movement
            WHERE inventorymovementorderid = :orderId AND inventorymovementtype = 'SALE'
            ORDER BY inventorymovementproductid
            """
        )
            .param("orderId", orderId)
            .query { rs, _ -> OrderSale(rs.getLong("inventorymovementproductid"), rs.getInt("quantity")) }
            .list()
}

/** One product's quantity taken from stock by an order. */
data class OrderSale(val productId: Long, val quantity: Int)
