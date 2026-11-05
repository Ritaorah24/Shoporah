package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object DeliveryMethodQuery {

    const val FIND_ACTIVE_BY_CODE = "SELECT * FROM ${PrefixConstant.ENTITY}DELIVERY_METHOD WHERE deliverymethodcode = :code AND deliverymethodstatus = 'ACTIVE'"

    const val FIND_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}DELIVERY_METHOD WHERE deliverymethodid = :id"

    const val FIND_ALL = "SELECT * FROM ${PrefixConstant.ENTITY}DELIVERY_METHOD ORDER BY deliverymethodname, deliverymethodid"

    const val FIND_ALL_ACTIVE = """
            SELECT * FROM ${PrefixConstant.ENTITY}DELIVERY_METHOD
            WHERE deliverymethodstatus = 'ACTIVE'
            ORDER BY deliverymethodfee, deliverymethodname, deliverymethodid
            """

    const val EXISTS_BY_CODE = "SELECT EXISTS (SELECT 1 FROM ${PrefixConstant.ENTITY}DELIVERY_METHOD WHERE deliverymethodcode = :code)"

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}DELIVERY_METHOD (
                deliverymethodcode, deliverymethodname, deliverymethoddescription, deliverymethodfee,
                deliverymethodestimatedmindays, deliverymethodestimatedmaxdays
            )
            VALUES (:code, :name, :description, :fee, :minDays, :maxDays)
            RETURNING deliverymethodid
            """

    const val UPDATE = """
            UPDATE ${PrefixConstant.ENTITY}DELIVERY_METHOD
            SET deliverymethodname = :name,
                deliverymethoddescription = :description,
                deliverymethodfee = :fee,
                deliverymethodestimatedmindays = :minDays,
                deliverymethodestimatedmaxdays = :maxDays,
                deliverymethodupdatedat = $SQL_NOW
            WHERE deliverymethodid = :id
            """

    const val UPDATE_STATUS = """
            UPDATE ${PrefixConstant.ENTITY}DELIVERY_METHOD
            SET deliverymethodstatus = :status,
                deliverymethodupdatedat = $SQL_NOW
            WHERE deliverymethodid = :id
            """
}
