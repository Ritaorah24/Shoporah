package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.model.request.AddCartItemRequest
import com.rita.ecommerceplatform.model.request.AddWishlistItemRequest
import com.rita.ecommerceplatform.model.request.MoveToCartRequest
import com.rita.ecommerceplatform.model.response.WishlistItemResponse
import com.rita.ecommerceplatform.model.response.WishlistResponse
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.entity.WishlistItem
import com.rita.ecommerceplatform.repository.database.CustomerRepository
import com.rita.ecommerceplatform.repository.database.ProductImageRepository
import com.rita.ecommerceplatform.repository.database.ProductRepository
import com.rita.ecommerceplatform.repository.database.WishlistRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The customer's wishlist (checkpoint 3.1, Step 3). Every change first locks the customer row, the same lock as
 * the cart, so two quick requests cannot pass the size limit together or move one item twice.
 */
@Service
class WishlistService(
    private val wishlistRepository: WishlistRepository,
    private val productRepository: ProductRepository,
    private val customerRepository: CustomerRepository,
    private val productImageRepository: ProductImageRepository,
    private val cartService: CartService,
) {

    /** A customer who never added anything gets an empty wishlist; no row is created just by looking. */
    fun get(customerId: Long): WishlistResponse {
        val wishlistId = wishlistRepository.findWishlistId(customerId) ?: return toResponse(emptyList())
        return toResponse(wishlistRepository.findItems(wishlistId))
    }

    /**
     * Saves a product the public can see, also when it is out of stock. Adding it again changes nothing
     * (it keeps its original date). At most MAX_ITEMS products.
     */
    @Transactional
    fun addItem(customerId: Long, request: AddWishlistItemRequest): WishlistResponse {
        customerRepository.lockById(customerId)
        val product = productRepository.findPublicById(request.productId!!) ?: throw NotFoundException("Product not found")
        val wishlistId = wishlistRepository.getOrCreateWishlistId(customerId)

        if (!wishlistRepository.contains(wishlistId, product.productId!!)) {
            if (wishlistRepository.countItems(wishlistId) >= MAX_ITEMS) {
                throw ConflictException("A wishlist can hold at most $MAX_ITEMS products")
            }
            wishlistRepository.addItem(wishlistId, product.productId!!)
            wishlistRepository.touch(wishlistId)
        }
        return toResponse(wishlistRepository.findItems(wishlistId))
    }

    @Transactional
    fun removeItem(customerId: Long, productId: Long) {
        customerRepository.lockById(customerId)
        val wishlistId = wishlistRepository.findWishlistId(customerId) ?: throw itemNotInWishlist()
        if (wishlistRepository.deleteItem(wishlistId, productId) == 0) throw itemNotInWishlist()
        wishlistRepository.touch(wishlistId)
    }

    /**
     * Adds the product to the cart with the cart's own rules (CartService.addItem: visible product, quantity added
     * to what is in the cart, never above the stock), then takes it off the wishlist. One transaction: if the cart
     * refuses (404 / 409), nothing changes and the item stays on the wishlist.
     */
    @Transactional
    fun moveToCart(customerId: Long, productId: Long, request: MoveToCartRequest?): WishlistResponse {
        customerRepository.lockById(customerId)
        val wishlistId = wishlistRepository.findWishlistId(customerId) ?: throw itemNotInWishlist()
        if (!wishlistRepository.contains(wishlistId, productId)) throw itemNotInWishlist()

        cartService.addItem(customerId, AddCartItemRequest(productId = productId, quantity = request?.quantity ?: 1))

        check(wishlistRepository.deleteItem(wishlistId, productId) == 1) { "Wishlist item $productId changed while locked" }
        wishlistRepository.touch(wishlistId)
        return toResponse(wishlistRepository.findItems(wishlistId))
    }

    private fun toResponse(items: List<WishlistItem>): WishlistResponse {
        // Each product's primary image, from one query for the whole wishlist.
        val images = productImageRepository.findByProductIds(items.map { it.productId!! })
        return WishlistResponse(
            items = items.map { item ->
                WishlistItemResponse(
                    productId = item.productId!!,
                    name = item.productName!!,
                    imageUrl = images[item.productId]?.firstOrNull { it.productImageIsPrimary!! }?.productImageUrl,
                    price = item.productPrice!!,
                    discountPrice = item.productDiscountPrice,
                    sellingPrice = item.productSellingPrice!!,
                    availability = item.availability()?.name,
                    available = item.available,
                    addedAt = item.wishlistItemCreatedAt!!,
                )
            },
            itemCount = items.size,
        )
    }

    private fun itemNotInWishlist() = NotFoundException("Item not in wishlist")

    companion object {
        const val MAX_ITEMS = 100
    }
}
