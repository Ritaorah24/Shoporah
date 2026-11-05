package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant

object InventoryMovementQuery {

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}INVENTORY_MOVEMENT (
                inventorymovementproductid, inventorymovementtype, inventorymovementquantitychange,
                inventorymovementstockafter, inventorymovementadminid, inventorymovementnote, inventorymovementorderid
            )
            VALUES (:productId, :type, :quantityChange, :stockAfter, :adminId, :note, :orderId)
            """

    const val FIND_BY_PRODUCT = """
            SELECT m.*, o.orderreference
            FROM ${PrefixConstant.ENTITY}INVENTORY_MOVEMENT m
            LEFT JOIN ${PrefixConstant.ENTITY}ORDER o ON o.orderid = m.inventorymovementorderid
            WHERE m.inventorymovementproductid = :productId
            ORDER BY m.inventorymovementcreatedat DESC, m.inventorymovementid DESC
            LIMIT :limit OFFSET :offset
            """

    const val COUNT_BY_PRODUCT = "SELECT COUNT(*) FROM ${PrefixConstant.ENTITY}INVENTORY_MOVEMENT WHERE inventorymovementproductid = :productId"

    const val FIND_SALES_BY_ORDER = """
            SELECT inventorymovementproductid, -inventorymovementquantitychange AS quantity
            FROM ${PrefixConstant.ENTITY}INVENTORY_MOVEMENT
            WHERE inventorymovementorderid = :orderId AND inventorymovementtype = 'SALE'
            ORDER BY inventorymovementproductid
            """
}
