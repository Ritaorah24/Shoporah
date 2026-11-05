package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object CategoryQuery {

    const val EXISTS_BY_NAME = """
            SELECT EXISTS (
                SELECT 1 FROM ${PrefixConstant.ENTITY}CATEGORY
                WHERE categoryname = :name
                  AND (CAST(:excludingId AS INTEGER) IS NULL OR categoryid <> :excludingId)
            )
            """

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}CATEGORY (categoryname, categorydescription, categorycreatedbyadminid)
            VALUES (:name, :description, :adminId)
            RETURNING categoryid
            """

    const val FIND_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}CATEGORY WHERE categoryid = :categoryId"

    const val FIND_BY_STATUSES = "SELECT * FROM ${PrefixConstant.ENTITY}CATEGORY WHERE categorystatus IN (<statuses>) ORDER BY categoryname"

    const val UPDATE = """
            UPDATE ${PrefixConstant.ENTITY}CATEGORY
            SET categoryname = :name,
                categorydescription = :description,
                categoryupdatedat = $SQL_NOW
            WHERE categoryid = :categoryId AND categorystatus <> 'DELETED'
            """

    const val UPDATE_STATUS = """
            UPDATE ${PrefixConstant.ENTITY}CATEGORY
            SET categorystatus = :status,
                categoryupdatedat = $SQL_NOW
            WHERE categoryid = :categoryId AND categorystatus <> 'DELETED'
            """

    const val HAS_PRODUCTS = """
            SELECT EXISTS (
                SELECT 1 FROM ${PrefixConstant.ENTITY}PRODUCT
                WHERE productcategoryid = :categoryId AND productstatus <> 'DELETED'
            )
            """
}
