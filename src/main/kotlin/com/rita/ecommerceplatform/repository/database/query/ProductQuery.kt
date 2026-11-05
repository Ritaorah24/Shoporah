package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object ProductQuery {

    const val SELECT_WITH_CATEGORY = """
        SELECT p.*, c.categoryname
        FROM ${PrefixConstant.ENTITY}PRODUCT p
        JOIN ${PrefixConstant.ENTITY}CATEGORY c ON c.categoryid = p.productcategoryid
    """

    const val EXISTS_BY_SKU = """
            SELECT EXISTS (
                SELECT 1 FROM ${PrefixConstant.ENTITY}PRODUCT
                WHERE productsku = :sku
                  AND (CAST(:excludingId AS BIGINT) IS NULL OR productid <> :excludingId)
            )
            """

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}PRODUCT (
                productcategoryid, productcreatedbyadminid, productname, productdescription, productsku,
                productprice, productdiscountprice, productstockquantity, productlowstockthreshold
            )
            VALUES (
                :categoryId, :adminId, :name, :description, :sku,
                :price, :discountPrice, :stockQuantity, :lowStockThreshold
            )
            RETURNING productid
            """

    const val FIND_BY_ID = "$SELECT_WITH_CATEGORY WHERE p.productid = :productId"

    const val FIND_PUBLIC_BY_ID = "$SELECT_WITH_CATEGORY WHERE p.productid = :productId AND p.productstatus = 'ACTIVE' AND c.categorystatus = 'ACTIVE'"

    const val FIND_BY_ID_FOR_UPDATE = "$SELECT_WITH_CATEGORY WHERE p.productid = :productId FOR UPDATE OF p"

    const val DECREASE_STOCK = """
            UPDATE ${PrefixConstant.ENTITY}PRODUCT
            SET productstockquantity = productstockquantity - :quantity, productupdatedat = $SQL_NOW
            WHERE productid = :productId AND productstockquantity >= :quantity
            RETURNING productstockquantity
            """

    const val INCREASE_STOCK = """
            UPDATE ${PrefixConstant.ENTITY}PRODUCT
            SET productstockquantity = productstockquantity + :quantity, productupdatedat = $SQL_NOW
            WHERE productid = :productId
            RETURNING productstockquantity
            """

    const val ADJUST_STOCK = """
            UPDATE ${PrefixConstant.ENTITY}PRODUCT
            SET productstockquantity = productstockquantity + :change, productupdatedat = $SQL_NOW
            WHERE productid = :productId AND productstatus <> 'DELETED' AND productstockquantity + :change >= 0
            RETURNING productstockquantity
            """

    const val UPDATE = """
            UPDATE ${PrefixConstant.ENTITY}PRODUCT
            SET productcategoryid = :categoryId,
                productname = :name,
                productdescription = :description,
                productsku = :sku,
                productprice = :price,
                productdiscountprice = :discountPrice,
                productstockquantity = :stockQuantity,
                productlowstockthreshold = :lowStockThreshold,
                productupdatedat = $SQL_NOW
            WHERE productid = :productId AND productstatus <> 'DELETED'
            """

    const val UPDATE_STATUS = """
            UPDATE ${PrefixConstant.ENTITY}PRODUCT
            SET productstatus = :status,
                productupdatedat = $SQL_NOW
            WHERE productid = :productId AND productstatus <> 'DELETED'
            """
}
