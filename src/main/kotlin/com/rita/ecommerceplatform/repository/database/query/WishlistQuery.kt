package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object WishlistQuery {

    const val FIND_WISHLIST_ID = "SELECT wishlistid FROM ${PrefixConstant.ENTITY}WISHLIST WHERE wishlistcustomerid = :customerId"

    const val GET_OR_CREATE_WISHLIST_ID = "INSERT INTO ${PrefixConstant.ENTITY}WISHLIST (wishlistcustomerid) VALUES (:customerId) ON CONFLICT (wishlistcustomerid) DO NOTHING"

    const val FIND_ITEMS = """
            SELECT wi.wishlistitemcreatedat, p.productid, p.productname, p.productprice, p.productdiscountprice,
                   p.productsellingprice, p.productstockquantity, p.productlowstockthreshold,
                   p.productstatus, c.categorystatus
            FROM ${PrefixConstant.ENTITY}WISHLIST_ITEM wi
            JOIN ${PrefixConstant.ENTITY}PRODUCT p ON p.productid = wi.wishlistitemproductid
            JOIN ${PrefixConstant.ENTITY}CATEGORY c ON c.categoryid = p.productcategoryid
            WHERE wi.wishlistitemwishlistid = :wishlistId
            ORDER BY wi.wishlistitemcreatedat DESC, wi.wishlistitemid DESC
            """

    const val COUNT_ITEMS = "SELECT COUNT(*) FROM ${PrefixConstant.ENTITY}WISHLIST_ITEM WHERE wishlistitemwishlistid = :wishlistId"

    const val CONTAINS = """
            SELECT EXISTS (
                SELECT 1 FROM ${PrefixConstant.ENTITY}WISHLIST_ITEM
                WHERE wishlistitemwishlistid = :wishlistId AND wishlistitemproductid = :productId
            )
            """

    const val ADD_ITEM = """
            INSERT INTO ${PrefixConstant.ENTITY}WISHLIST_ITEM (wishlistitemwishlistid, wishlistitemproductid)
            VALUES (:wishlistId, :productId)
            ON CONFLICT (wishlistitemwishlistid, wishlistitemproductid) DO NOTHING
            """

    const val DELETE_ITEM = "DELETE FROM ${PrefixConstant.ENTITY}WISHLIST_ITEM WHERE wishlistitemwishlistid = :wishlistId AND wishlistitemproductid = :productId"

    const val TOUCH = "UPDATE ${PrefixConstant.ENTITY}WISHLIST SET wishlistupdatedat = $SQL_NOW WHERE wishlistid = :wishlistId"
}
