package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object ProductImageQuery {

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}PRODUCT_IMAGE (
                productimageproductid, productimageurl, productimagestoragekey, productimagealttext,
                productimagesortorder, productimageisprimary
            )
            VALUES (:productId, :url, :storageKey, :altText, :sortOrder, :isPrimary)
            RETURNING productimageid
            """

    const val FIND_BY_PRODUCT = """
            SELECT * FROM ${PrefixConstant.ENTITY}PRODUCT_IMAGE
            WHERE productimageproductid = :productId
            ORDER BY productimageisprimary DESC, productimagesortorder, productimageid
            """

    const val FIND_BY_PRODUCT_IDS = """
            SELECT * FROM ${PrefixConstant.ENTITY}PRODUCT_IMAGE
            WHERE productimageproductid IN (<productIds>)
            ORDER BY productimageproductid, productimageisprimary DESC, productimagesortorder, productimageid
            """

    const val UPDATE = """
            UPDATE ${PrefixConstant.ENTITY}PRODUCT_IMAGE
            SET productimagealttext = :altText, productimagesortorder = :sortOrder, productimageupdatedat = $SQL_NOW
            WHERE productimageid = :imageId
            """

    const val MAKE_PRIMARY = """
            UPDATE ${PrefixConstant.ENTITY}PRODUCT_IMAGE
            SET productimageisprimary = FALSE, productimageupdatedat = $SQL_NOW
            WHERE productimageproductid = :productId AND productimageisprimary AND productimageid <> :imageId
            """

    const val MAKE_PRIMARY_2 = """
            UPDATE ${PrefixConstant.ENTITY}PRODUCT_IMAGE
            SET productimageisprimary = TRUE, productimageupdatedat = $SQL_NOW
            WHERE productimageid = :imageId AND NOT productimageisprimary
            """

    const val FIND_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}PRODUCT_IMAGE WHERE productimageid = :imageId AND productimageproductid = :productId"

    const val COUNT_BY_PRODUCT = "SELECT COUNT(*) FROM ${PrefixConstant.ENTITY}PRODUCT_IMAGE WHERE productimageproductid = :productId"

    const val NEXT_SORT_ORDER = "SELECT COALESCE(MAX(productimagesortorder) + 1, 0) FROM ${PrefixConstant.ENTITY}PRODUCT_IMAGE WHERE productimageproductid = :productId"

    const val DELETE = "DELETE FROM ${PrefixConstant.ENTITY}PRODUCT_IMAGE WHERE productimageid = :imageId"

    const val PROMOTE_FIRST_IF_NO_PRIMARY = """
            UPDATE ${PrefixConstant.ENTITY}PRODUCT_IMAGE
            SET productimageisprimary = TRUE, productimageupdatedat = $SQL_NOW
            WHERE productimageid = (
                SELECT productimageid FROM ${PrefixConstant.ENTITY}PRODUCT_IMAGE
                WHERE productimageproductid = :productId
                ORDER BY productimagesortorder, productimageid
                LIMIT 1
            )
            AND NOT EXISTS (
                SELECT 1 FROM ${PrefixConstant.ENTITY}PRODUCT_IMAGE WHERE productimageproductid = :productId AND productimageisprimary
            )
            """
}
