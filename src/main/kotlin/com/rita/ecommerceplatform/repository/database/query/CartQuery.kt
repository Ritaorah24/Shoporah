package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object CartQuery {

    const val FIND_CART_ID = "SELECT cartid FROM ${PrefixConstant.ENTITY}CART WHERE cartcustomerid = :customerId"

    const val GET_OR_CREATE_CART_ID = "INSERT INTO ${PrefixConstant.ENTITY}CART (cartcustomerid) VALUES (:customerId) ON CONFLICT (cartcustomerid) DO NOTHING"

    const val FIND_ITEMS = """
            SELECT ci.cartitemquantity, p.productid, p.productname, p.productsku, p.productprice,
                   p.productdiscountprice, p.productsellingprice, p.productstockquantity,
                   p.productstatus, c.categorystatus
            FROM ${PrefixConstant.ENTITY}CART_ITEM ci
            JOIN ${PrefixConstant.ENTITY}PRODUCT p ON p.productid = ci.cartitemproductid
            JOIN ${PrefixConstant.ENTITY}CATEGORY c ON c.categoryid = p.productcategoryid
            WHERE ci.cartitemcartid = :cartId
            ORDER BY ci.cartitemcreatedat, ci.cartitemid
            """

    const val FIND_QUANTITY = "SELECT cartitemquantity FROM ${PrefixConstant.ENTITY}CART_ITEM WHERE cartitemcartid = :cartId AND cartitemproductid = :productId"

    const val UPSERT_ITEM = """
            INSERT INTO ${PrefixConstant.ENTITY}CART_ITEM (cartitemcartid, cartitemproductid, cartitemquantity)
            VALUES (:cartId, :productId, :quantity)
            ON CONFLICT (cartitemcartid, cartitemproductid)
            DO UPDATE SET cartitemquantity = EXCLUDED.cartitemquantity,
                          cartitemupdatedat = $SQL_NOW
            """

    const val DELETE_ITEM = "DELETE FROM ${PrefixConstant.ENTITY}CART_ITEM WHERE cartitemcartid = :cartId AND cartitemproductid = :productId"

    const val DELETE_ALL_ITEMS = "DELETE FROM ${PrefixConstant.ENTITY}CART_ITEM WHERE cartitemcartid = :cartId"

    const val TOUCH = "UPDATE ${PrefixConstant.ENTITY}CART SET cartupdatedat = $SQL_NOW WHERE cartid = :cartId"
}
