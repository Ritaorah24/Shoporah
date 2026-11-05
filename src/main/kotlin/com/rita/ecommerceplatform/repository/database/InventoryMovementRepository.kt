package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.model.entity.InventoryMovement
import com.rita.ecommerceplatform.model.entity.InventoryMovementType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

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

    /**
     * One page of a product's stock history, newest first, with the order reference when an order caused it
     * (uses ix__ec_inventorymovement__productid_createdat).
     */
    fun findByProduct(productId: Long, limit: Int, offset: Long): List<InventoryMovement> =
        jdbcClient.sql(
            """
            SELECT m.*, o.orderreference
            FROM public.ec_inventory_movement m
            LEFT JOIN public.ec_order o ON o.orderid = m.inventorymovementorderid
            WHERE m.inventorymovementproductid = :productId
            ORDER BY m.inventorymovementcreatedat DESC, m.inventorymovementid DESC
            LIMIT :limit OFFSET :offset
            """
        )
            .param("productId", productId)
            .param("limit", limit)
            .param("offset", offset)
            .query { rs, _ ->
                InventoryMovement(
                    movementId = rs.getLong("inventorymovementid"),
                    productId = rs.getLong("inventorymovementproductid"),
                    type = InventoryMovementType.valueOf(rs.getString("inventorymovementtype").uppercase()),
                    quantityChange = rs.getInt("inventorymovementquantitychange"),
                    stockAfter = rs.getInt("inventorymovementstockafter"),
                    orderId = rs.getObject("inventorymovementorderid", Long::class.javaObjectType),
                    orderReference = rs.getString("orderreference"),
                    adminId = rs.getObject("inventorymovementadminid", Int::class.javaObjectType),
                    note = rs.getString("inventorymovementnote"),
                    createdAt = rs.getObject("inventorymovementcreatedat", LocalDateTime::class.java),
                )
            }
            .list()

    fun countByProduct(productId: Long): Long =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_inventory_movement WHERE inventorymovementproductid = :productId")
            .param("productId", productId)
            .query(Long::class.javaObjectType)
            .single()

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
