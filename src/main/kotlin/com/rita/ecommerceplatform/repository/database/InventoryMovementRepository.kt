package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.InventoryMovementQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.RowMapper
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.InventoryMovement
import com.rita.ecommerceplatform.model.entity.InventoryMovementType
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** ec_inventory_movement is an append-only log of stock changes (D7): rows are inserted, never updated. */
interface InventoryMovementRepository {
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
    )

    /**
     * One page of a product's stock history, newest first, with the order reference when an order caused it
     * (uses ix__ec_inventorymovement__productid_createdat).
     */
    fun findByProduct(productId: Long, limit: Int, offset: Long): List<InventoryMovement>

    fun countByProduct(productId: Long): Long

    /** What the order's payment took from stock (its SALE movements), by productId; empty if nothing was taken. */
    fun findSalesByOrder(orderId: Long): List<OrderSale>
}

@Repository
class InventoryMovementRepositoryImpl(private val jdbi: Jdbi) : InventoryMovementRepository {

    override fun insert(
        productId: Long,
        type: InventoryMovementType,
        quantityChange: Int,
        stockAfter: Int,
        adminId: Int?,
        note: String?,
        orderId: Long?,
    ) {
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(InventoryMovementQuery.INSERT)
                .bind("productId", productId)
                .bind("type", type.name)
                .bind("quantityChange", quantityChange)
                .bind("stockAfter", stockAfter)
                .bind("adminId", adminId)
                .bind("note", note)
                .bind("orderId", orderId)
                .execute()
        }
    }

    override fun findByProduct(productId: Long, limit: Int, offset: Long): List<InventoryMovement> =
        jdbi.withHandle<List<InventoryMovement>, Exception> { handle ->
            handle.createQuery(InventoryMovementQuery.FIND_BY_PRODUCT)
                .bind("productId", productId)
                .bind("limit", limit)
                .bind("offset", offset)
            .mapTo<InventoryMovement>()
            .list()
        }

    override fun countByProduct(productId: Long): Long =
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(InventoryMovementQuery.COUNT_BY_PRODUCT)
                .bind("productId", productId)
                .mapTo<Long>()
                .one()
        }

    override fun findSalesByOrder(orderId: Long): List<OrderSale> =
        jdbi.withHandle<List<OrderSale>, Exception> { handle ->
            handle.createQuery(InventoryMovementQuery.FIND_SALES_BY_ORDER)
                .bind("orderId", orderId)
            .map(RowMapper { rs, _ -> OrderSale(rs.getLong("inventorymovementproductid"), rs.getInt("quantity")) })
            .list()
        }
}

/** One product's quantity taken from stock by an order. */
data class OrderSale(val productId: Long, val quantity: Int)
