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
    ) {
        jdbcClient.sql(
            """
            INSERT INTO public.ec_inventory_movement (
                inventorymovementproductid, inventorymovementtype, inventorymovementquantitychange,
                inventorymovementstockafter, inventorymovementadminid, inventorymovementnote
            )
            VALUES (:productId, :type, :quantityChange, :stockAfter, :adminId, :note)
            """
        )
            .param("productId", productId)
            .param("type", type.name)
            .param("quantityChange", quantityChange)
            .param("stockAfter", stockAfter)
            .param("adminId", adminId)
            .param("note", note)
            .update()
    }
}
