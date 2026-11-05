package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/**
 * One row of public.ec_inventory_movement, plus the order's reference when an order caused it.
 * quantityChange is signed (+ in, - out); stockAfter is the product's stock right after.
 */
data class InventoryMovement(
    val movementId: Long,
    val productId: Long,
    val type: InventoryMovementType,
    val quantityChange: Int,
    val stockAfter: Int,
    val orderId: Long?,
    val orderReference: String?,
    val adminId: Int?,
    val note: String?,
    val createdAt: LocalDateTime,
)

/**
 * Allowed values of ec_inventory_movement.inventorymovementtype (ck__ec_inventorymovement__type).
 * The table is an append-only log, so rows are only ever inserted (an order's sales are read as OrderSale).
 * 1.2 uses RESTOCK (stock goes up) and ADJUSTMENT (stock goes down); the others arrive with orders.
 */
enum class InventoryMovementType { RESTOCK, ADJUSTMENT, SALE, ORDER_CANCELLED, REFUND_RETURN }
