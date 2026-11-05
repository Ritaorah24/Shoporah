package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant

object OrderItemQuery {

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}ORDER_ITEM (
                orderitemorderid, orderitemproductid, orderitemproductname, orderitemproductsku,
                orderitemunitprice, orderitemquantity
            )
            VALUES (:orderId, :productId, :productName, :productSku, :unitPrice, :quantity)
            """

    const val FIND_BY_ORDER_ID = "SELECT * FROM ${PrefixConstant.ENTITY}ORDER_ITEM WHERE orderitemorderid = :orderId ORDER BY orderitemid"

    const val QUANTITIES_BY_ORDER_IDS = """
            SELECT orderitemorderid, SUM(orderitemquantity) AS quantity
            FROM ${PrefixConstant.ENTITY}ORDER_ITEM
            WHERE orderitemorderid IN (<orderIds>)
            GROUP BY orderitemorderid
            """
}
