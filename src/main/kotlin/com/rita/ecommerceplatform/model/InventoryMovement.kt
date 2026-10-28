package com.rita.ecommerceplatform.model

/**
 * Allowed values of ec_inventory_movement.inventorymovementtype (ck__ec_inventorymovement__type).
 * The table is an append-only log, so rows are only ever inserted (an order's sales are read as OrderSale).
 * 1.2 uses RESTOCK (stock goes up) and ADJUSTMENT (stock goes down); the others arrive with orders.
 */
enum class InventoryMovementType { RESTOCK, ADJUSTMENT, SALE, ORDER_CANCELLED, REFUND_RETURN }
