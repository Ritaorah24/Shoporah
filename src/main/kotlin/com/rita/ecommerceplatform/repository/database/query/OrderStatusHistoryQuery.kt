package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant

object OrderStatusHistoryQuery {

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}ORDER_STATUS_HISTORY (
                orderstatushistoryorderid, orderstatushistoryfromstatus, orderstatushistorytostatus,
                orderstatushistorychangedbyadminid, orderstatushistorychangedbycustomerid, orderstatushistorynote
            )
            VALUES (:orderId, :fromStatus, :toStatus, :adminId, :customerId, :note)
            """

    const val FIND_BY_ORDER_ID = """
            SELECT * FROM ${PrefixConstant.ENTITY}ORDER_STATUS_HISTORY
            WHERE orderstatushistoryorderid = :orderId
            ORDER BY orderstatushistorycreatedat, orderstatushistoryid
            """
}
