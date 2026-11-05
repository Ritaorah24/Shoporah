package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.utility.SQL_NOW
import com.rita.ecommerceplatform.model.entity.CartItem
import com.rita.ecommerceplatform.model.entity.CategoryStatus
import com.rita.ecommerceplatform.model.entity.ProductStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** ec_cart (one per customer, reused for the customer's lifetime) and its ec_cart_item rows. */
@Repository
class CartRepository(private val jdbcClient: JdbcClient) {

    private val itemRowMapper = RowMapper { rs, _ ->
        CartItem(
            productId = rs.getLong("productid"),
            quantity = rs.getInt("cartitemquantity"),
            productName = rs.getString("productname"),
            productSku = rs.getString("productsku"),
            price = rs.getBigDecimal("productprice"),
            discountPrice = rs.getBigDecimal("productdiscountprice"),
            sellingPrice = rs.getBigDecimal("productsellingprice"),
            stockQuantity = rs.getInt("productstockquantity"),
            productStatus = ProductStatus.valueOf(rs.getString("productstatus").uppercase()),
            categoryStatus = CategoryStatus.valueOf(rs.getString("categorystatus").uppercase()),
        )
    }

    fun findCartId(customerId: Long): Long? =
        jdbcClient.sql("SELECT cartid FROM public.ec_cart WHERE cartcustomerid = :customerId")
            .param("customerId", customerId)
            .query(Long::class.javaObjectType)
            .optional()
            .orElse(null)

    /**
     * The customer's cart id, creating the cart on first use. ON CONFLICT DO NOTHING makes two
     * concurrent first requests safe: uq__ec_cart__customerid lets only one insert win, and both
     * then read the same row.
     */
    fun getOrCreateCartId(customerId: Long): Long {
        jdbcClient.sql("INSERT INTO public.ec_cart (cartcustomerid) VALUES (:customerId) ON CONFLICT (cartcustomerid) DO NOTHING")
            .param("customerId", customerId)
            .update()
        return findCartId(customerId)!!
    }

    /** The cart's items with live product data, oldest line first (the order they were added). */
    fun findItems(cartId: Long): List<CartItem> =
        jdbcClient.sql(
            """
            SELECT ci.cartitemquantity, p.productid, p.productname, p.productsku, p.productprice,
                   p.productdiscountprice, p.productsellingprice, p.productstockquantity,
                   p.productstatus, c.categorystatus
            FROM public.ec_cart_item ci
            JOIN public.ec_product p ON p.productid = ci.cartitemproductid
            JOIN public.ec_category c ON c.categoryid = p.productcategoryid
            WHERE ci.cartitemcartid = :cartId
            ORDER BY ci.cartitemcreatedat, ci.cartitemid
            """
        )
            .param("cartId", cartId)
            .query(itemRowMapper)
            .list()

    /** Current quantity of a product in the cart, or null if it is not in the cart. */
    fun findQuantity(cartId: Long, productId: Long): Int? =
        jdbcClient.sql(
            "SELECT cartitemquantity FROM public.ec_cart_item WHERE cartitemcartid = :cartId AND cartitemproductid = :productId"
        )
            .param("cartId", cartId)
            .param("productId", productId)
            .query(Int::class.javaObjectType)
            .optional()
            .orElse(null)

    /**
     * Sets a product's quantity in the cart, adding the line if it is not there yet
     * (uq__ec_cartitem__cartid_productid keeps one line per product). quantity must be > 0.
     */
    fun upsertItem(cartId: Long, productId: Long, quantity: Int) {
        jdbcClient.sql(
            """
            INSERT INTO public.ec_cart_item (cartitemcartid, cartitemproductid, cartitemquantity)
            VALUES (:cartId, :productId, :quantity)
            ON CONFLICT (cartitemcartid, cartitemproductid)
            DO UPDATE SET cartitemquantity = EXCLUDED.cartitemquantity,
                          cartitemupdatedat = $SQL_NOW
            """
        )
            .param("cartId", cartId)
            .param("productId", productId)
            .param("quantity", quantity)
            .update()
    }

    /** Removes one product from the cart. Returns rows deleted (0 or 1). */
    fun deleteItem(cartId: Long, productId: Long): Int =
        jdbcClient.sql("DELETE FROM public.ec_cart_item WHERE cartitemcartid = :cartId AND cartitemproductid = :productId")
            .param("cartId", cartId)
            .param("productId", productId)
            .update()

    /** Empties the cart (clear cart, and after checkout). The cart row itself is kept. */
    fun deleteAllItems(cartId: Long): Int =
        jdbcClient.sql("DELETE FROM public.ec_cart_item WHERE cartitemcartid = :cartId")
            .param("cartId", cartId)
            .update()

    /** Records that the cart changed. */
    fun touch(cartId: Long) {
        jdbcClient.sql("UPDATE public.ec_cart SET cartupdatedat = $SQL_NOW WHERE cartid = :cartId")
            .param("cartId", cartId)
            .update()
    }
}
