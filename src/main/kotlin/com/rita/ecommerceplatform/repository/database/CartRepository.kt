package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.CartQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.CartItem
import com.rita.ecommerceplatform.model.entity.CategoryStatus
import com.rita.ecommerceplatform.model.entity.ProductStatus
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository

/** ec_cart (one per customer, reused for the customer's lifetime) and its ec_cart_item rows. */
interface CartRepository {
    fun findCartId(customerId: Long): Long?

    /**
     * The customer's cart id, creating the cart on first use. ON CONFLICT DO NOTHING makes two
     * concurrent first requests safe: uq__ec_cart__customerid lets only one insert win, and both
     * then read the same row.
     */
    fun getOrCreateCartId(customerId: Long): Long

    /** The cart's items with live product data, oldest line first (the order they were added). */
    fun findItems(cartId: Long): List<CartItem>

    /** Current quantity of a product in the cart, or null if it is not in the cart. */
    fun findQuantity(cartId: Long, productId: Long): Int?

    /**
     * Sets a product's quantity in the cart, adding the line if it is not there yet
     * (uq__ec_cartitem__cartid_productid keeps one line per product). quantity must be > 0.
     */
    fun upsertItem(cartId: Long, productId: Long, quantity: Int)

    /** Removes one product from the cart. Returns rows deleted (0 or 1). */
    fun deleteItem(cartId: Long, productId: Long): Int

    /** Empties the cart (clear cart, and after checkout). The cart row itself is kept. */
    fun deleteAllItems(cartId: Long): Int

    /** Records that the cart changed. */
    fun touch(cartId: Long)
}

@Repository
class CartRepositoryImpl(private val jdbi: Jdbi) : CartRepository {

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

    override fun findCartId(customerId: Long): Long? =
        jdbi.withHandle<Long?, Exception> { handle ->
            handle.createQuery(CartQuery.FIND_CART_ID)
                .bind("customerId", customerId)
                .mapTo<Long>()
                .findOne()
                .orElse(null)
        }

    override fun getOrCreateCartId(customerId: Long): Long {
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CartQuery.GET_OR_CREATE_CART_ID)
                .bind("customerId", customerId)
                .execute()
        }
        return findCartId(customerId)!!
    }

    override fun findItems(cartId: Long): List<CartItem> =
        jdbi.withHandle<List<CartItem>, Exception> { handle ->
            handle.createQuery(CartQuery.FIND_ITEMS)
                .bind("cartId", cartId)
                .map(itemRowMapper)
                .list()
        }

    override fun findQuantity(cartId: Long, productId: Long): Int? =
        jdbi.withHandle<Int?, Exception> { handle ->
            handle.createQuery(CartQuery.FIND_QUANTITY)
                .bind("cartId", cartId)
                .bind("productId", productId)
                .mapTo<Int>()
                .findOne()
                .orElse(null)
        }

    override fun upsertItem(cartId: Long, productId: Long, quantity: Int) {
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CartQuery.UPSERT_ITEM)
                .bind("cartId", cartId)
                .bind("productId", productId)
                .bind("quantity", quantity)
                .execute()
        }
    }

    override fun deleteItem(cartId: Long, productId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CartQuery.DELETE_ITEM)
                .bind("cartId", cartId)
                .bind("productId", productId)
                .execute()
        }

    override fun deleteAllItems(cartId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CartQuery.DELETE_ALL_ITEMS)
                .bind("cartId", cartId)
                .execute()
        }

    override fun touch(cartId: Long) {
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CartQuery.TOUCH)
                .bind("cartId", cartId)
                .execute()
        }
    }
}
