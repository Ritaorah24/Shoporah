package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.CategoryStatus
import com.rita.ecommerceplatform.model.ProductStatus
import com.rita.ecommerceplatform.model.WishlistItem
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** ec_wishlist (one per customer, reused for the customer's lifetime) and its ec_wishlist_item rows. */
@Repository
class WishlistRepository(private val jdbcClient: JdbcClient) {

    private val itemRowMapper = RowMapper { rs, _ ->
        WishlistItem(
            productId = rs.getLong("productid"),
            productName = rs.getString("productname"),
            price = rs.getBigDecimal("productprice"),
            discountPrice = rs.getBigDecimal("productdiscountprice"),
            sellingPrice = rs.getBigDecimal("productsellingprice"),
            stockQuantity = rs.getInt("productstockquantity"),
            lowStockThreshold = rs.getObject("productlowstockthreshold", Int::class.javaObjectType),
            productStatus = ProductStatus.valueOf(rs.getString("productstatus").uppercase()),
            categoryStatus = CategoryStatus.valueOf(rs.getString("categorystatus").uppercase()),
            addedAt = rs.getObject("wishlistitemcreatedat", LocalDateTime::class.java),
        )
    }

    fun findWishlistId(customerId: Long): Long? =
        jdbcClient.sql("SELECT wishlistid FROM public.ec_wishlist WHERE wishlistcustomerid = :customerId")
            .param("customerId", customerId)
            .query(Long::class.javaObjectType)
            .optional()
            .orElse(null)

    /** The customer's wishlist id, creating it on first use (safe when two first requests race, like the cart). */
    fun getOrCreateWishlistId(customerId: Long): Long {
        jdbcClient.sql(
            "INSERT INTO public.ec_wishlist (wishlistcustomerid) VALUES (:customerId) ON CONFLICT (wishlistcustomerid) DO NOTHING"
        )
            .param("customerId", customerId)
            .update()
        return findWishlistId(customerId)!!
    }

    /** The wishlist's items with live product data, newest first. */
    fun findItems(wishlistId: Long): List<WishlistItem> =
        jdbcClient.sql(
            """
            SELECT wi.wishlistitemcreatedat, p.productid, p.productname, p.productprice, p.productdiscountprice,
                   p.productsellingprice, p.productstockquantity, p.productlowstockthreshold,
                   p.productstatus, c.categorystatus
            FROM public.ec_wishlist_item wi
            JOIN public.ec_product p ON p.productid = wi.wishlistitemproductid
            JOIN public.ec_category c ON c.categoryid = p.productcategoryid
            WHERE wi.wishlistitemwishlistid = :wishlistId
            ORDER BY wi.wishlistitemcreatedat DESC, wi.wishlistitemid DESC
            """
        )
            .param("wishlistId", wishlistId)
            .query(itemRowMapper)
            .list()

    fun countItems(wishlistId: Long): Int =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_wishlist_item WHERE wishlistitemwishlistid = :wishlistId")
            .param("wishlistId", wishlistId)
            .query(Int::class.javaObjectType)
            .single()

    fun contains(wishlistId: Long, productId: Long): Boolean =
        jdbcClient.sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM public.ec_wishlist_item
                WHERE wishlistitemwishlistid = :wishlistId AND wishlistitemproductid = :productId
            )
            """
        )
            .param("wishlistId", wishlistId)
            .param("productId", productId)
            .query(Boolean::class.javaObjectType)
            .single()

    /**
     * Adds the product unless it is already there (uq__ec_wishlistitem__wishlistid_productid keeps one row per
     * product; the existing row keeps its date). Returns rows inserted (0 or 1).
     */
    fun addItem(wishlistId: Long, productId: Long): Int =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_wishlist_item (wishlistitemwishlistid, wishlistitemproductid)
            VALUES (:wishlistId, :productId)
            ON CONFLICT (wishlistitemwishlistid, wishlistitemproductid) DO NOTHING
            """
        )
            .param("wishlistId", wishlistId)
            .param("productId", productId)
            .update()

    /** Removes one product from the wishlist. Returns rows deleted (0 or 1). */
    fun deleteItem(wishlistId: Long, productId: Long): Int =
        jdbcClient.sql(
            "DELETE FROM public.ec_wishlist_item WHERE wishlistitemwishlistid = :wishlistId AND wishlistitemproductid = :productId"
        )
            .param("wishlistId", wishlistId)
            .param("productId", productId)
            .update()

    /** Records that the wishlist changed. */
    fun touch(wishlistId: Long) {
        jdbcClient.sql("UPDATE public.ec_wishlist SET wishlistupdatedat = $SQL_NOW WHERE wishlistid = :wishlistId")
            .param("wishlistId", wishlistId)
            .update()
    }
}
