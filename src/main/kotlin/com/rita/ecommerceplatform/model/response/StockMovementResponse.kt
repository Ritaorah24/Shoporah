package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.InventoryMovement
import java.time.LocalDateTime

/**
 * One change in a product's stock: RESTOCK / ADJUSTMENT (admin), SALE (payment), ORDER_CANCELLED or REFUND_RETURN.
 * orderReference is set when an order caused it; adminId when an admin did.
 */
data class StockMovementResponse(
    val movementId: Long,
    val type: String,
    val quantityChange: Int,
    val stockAfter: Int,
    val orderId: Long?,
    val orderReference: String?,
    val adminId: Int?,
    val note: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(movement: InventoryMovement) = StockMovementResponse(
            movementId = movement.movementId,
            type = movement.type.name,
            quantityChange = movement.quantityChange,
            stockAfter = movement.stockAfter,
            orderId = movement.orderId,
            orderReference = movement.orderReference,
            adminId = movement.adminId,
            note = movement.note,
            createdAt = movement.createdAt,
        )
    }
}
