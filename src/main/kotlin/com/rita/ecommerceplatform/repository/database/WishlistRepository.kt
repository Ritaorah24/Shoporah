package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.WishlistQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.CategoryStatus
import com.rita.ecommerceplatform.model.entity.ProductStatus
import com.rita.ecommerceplatform.model.entity.WishlistItem
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** ec_wishlist (one per customer, reused for the customer's lifetime) and its ec_wishlist_item rows. */
interface WishlistRepository {
    fun findWishlistId(customerId: Long): Long?

    /** The customer's wishlist id, creating it on first use (safe when two first requests race, like the cart). */
    fun getOrCreateWishlistId(customerId: Long): Long

    /** The wishlist's items with live product data, newest first. */
    fun findItems(wishlistId: Long): List<WishlistItem>

    fun countItems(wishlistId: Long): Int

    fun contains(wishlistId: Long, productId: Long): Boolean

    /**
     * Adds the product unless it is already there (uq__ec_wishlistitem__wishlistid_productid keeps one row per
     * product; the existing row keeps its date). Returns rows inserted (0 or 1).
     */
    fun addItem(wishlistId: Long, productId: Long): Int

    /** Removes one product from the wishlist. Returns rows deleted (0 or 1). */
    fun deleteItem(wishlistId: Long, productId: Long): Int

    /** Records that the wishlist changed. */
    fun touch(wishlistId: Long)
}

@Repository
class WishlistRepositoryImpl(private val jdbi: Jdbi) : WishlistRepository {

    override fun findWishlistId(customerId: Long): Long? =
        jdbi.withHandle<Long?, Exception> { handle ->
            handle.createQuery(WishlistQuery.FIND_WISHLIST_ID)
                .bind("customerId", customerId)
                .mapTo<Long>()
                .findOne()
                .orElse(null)
        }

    override fun getOrCreateWishlistId(customerId: Long): Long {
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(WishlistQuery.GET_OR_CREATE_WISHLIST_ID)
                .bind("customerId", customerId)
                .execute()
        }
        return findWishlistId(customerId)!!
    }

    override fun findItems(wishlistId: Long): List<WishlistItem> =
        jdbi.withHandle<List<WishlistItem>, Exception> { handle ->
            handle.createQuery(WishlistQuery.FIND_ITEMS)
                .bind("wishlistId", wishlistId)
                .mapTo<WishlistItem>()
                .list()
        }

    override fun countItems(wishlistId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery(WishlistQuery.COUNT_ITEMS)
                .bind("wishlistId", wishlistId)
                .mapTo<Int>()
                .one()
        }

    override fun contains(wishlistId: Long, productId: Long): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(WishlistQuery.CONTAINS)
                .bind("wishlistId", wishlistId)
                .bind("productId", productId)
                .mapTo<Boolean>()
                .one()
        }

    override fun addItem(wishlistId: Long, productId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(WishlistQuery.ADD_ITEM)
                .bind("wishlistId", wishlistId)
                .bind("productId", productId)
                .execute()
        }

    override fun deleteItem(wishlistId: Long, productId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(WishlistQuery.DELETE_ITEM)
                .bind("wishlistId", wishlistId)
                .bind("productId", productId)
                .execute()
        }

    override fun touch(wishlistId: Long) {
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(WishlistQuery.TOUCH)
                .bind("wishlistId", wishlistId)
                .execute()
        }
    }
}
