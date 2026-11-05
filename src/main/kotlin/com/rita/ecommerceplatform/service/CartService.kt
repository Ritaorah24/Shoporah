package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.model.request.AddCartItemRequest
import com.rita.ecommerceplatform.model.request.UpdateCartItemRequest
import com.rita.ecommerceplatform.model.response.CartItemResponse
import com.rita.ecommerceplatform.model.response.CartResponse
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.entity.CartItem
import com.rita.ecommerceplatform.model.entity.CategoryStatus
import com.rita.ecommerceplatform.model.entity.ProductStatus
import com.rita.ecommerceplatform.repository.database.CartRepository
import com.rita.ecommerceplatform.repository.database.CustomerRepository
import com.rita.ecommerceplatform.repository.database.ProductImageRepository
import com.rita.ecommerceplatform.repository.database.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** Why a cart item cannot be bought as it is. Checkout (Step 5) uses the same rules through issueOf. */
enum class CartItemIssue { PRODUCT_UNAVAILABLE, OUT_OF_STOCK, INSUFFICIENT_STOCK }

/**
 * The customer's cart. Every change first locks the customer row (C2), so two requests for the same
 * customer cannot both pass the stock check. Prices are always read from ec_product, never from a request.
 */
@Service
class CartService(
    private val cartRepository: CartRepository,
    private val productRepository: ProductRepository,
    private val customerRepository: CustomerRepository,
    private val productImageRepository: ProductImageRepository,
) {

    /** A customer who never added anything gets an empty cart; no cart row is created just by looking. */
    fun getCart(customerId: Long): CartResponse {
        val cartId = cartRepository.findCartId(customerId) ?: return toResponse(emptyList())
        return toResponse(cartRepository.findItems(cartId))
    }

    /** Adds to the quantity already in the cart (one line per product). */
    @Transactional
    fun addItem(customerId: Long, request: AddCartItemRequest): CartResponse {
        customerRepository.lockById(customerId)
        // Only products the public can see (active, in an active category) can be added.
        val product = productRepository.findPublicById(request.productId!!) ?: throw NotFoundException("Product not found")
        val cartId = cartRepository.getOrCreateCartId(customerId)

        val inCart = cartRepository.findQuantity(cartId, product.productId!!) ?: 0
        // Long, so a huge requested quantity cannot overflow past the stock check.
        val newQuantity = inCart.toLong() + request.quantity!!
        checkStock(product.productStockQuantity!!, newQuantity)

        cartRepository.upsertItem(cartId, product.productId!!, newQuantity.toInt())
        cartRepository.touch(cartId)
        return toResponse(cartRepository.findItems(cartId))
    }

    /** Replaces the quantity of a product already in the cart. */
    @Transactional
    fun updateItem(customerId: Long, productId: Long, request: UpdateCartItemRequest): CartResponse {
        customerRepository.lockById(customerId)
        val cartId = cartRepository.findCartId(customerId) ?: throw itemNotInCart()
        cartRepository.findQuantity(cartId, productId) ?: throw itemNotInCart()
        val product = productRepository.findPublicById(productId) ?: throw NotFoundException("Product not found")

        val quantity = request.quantity!!
        checkStock(product.productStockQuantity!!, quantity.toLong())

        cartRepository.upsertItem(cartId, productId, quantity)
        cartRepository.touch(cartId)
        return toResponse(cartRepository.findItems(cartId))
    }

    @Transactional
    fun removeItem(customerId: Long, productId: Long) {
        customerRepository.lockById(customerId)
        val cartId = cartRepository.findCartId(customerId) ?: throw itemNotInCart()
        if (cartRepository.deleteItem(cartId, productId) == 0) throw itemNotInCart()
        cartRepository.touch(cartId)
    }

    /** Empties the cart. Clearing an empty (or never used) cart is fine. */
    @Transactional
    fun clear(customerId: Long) {
        customerRepository.lockById(customerId)
        val cartId = cartRepository.findCartId(customerId) ?: return
        cartRepository.deleteAllItems(cartId)
        cartRepository.touch(cartId)
    }

    /**
     * Why this item cannot be bought as it is, or null if it can. The single place these rules live:
     * the cart view and checkout both use it, so they can never disagree.
     */
    fun issueOf(item: CartItem): CartItemIssue? = when {
        item.productStatus != ProductStatus.ACTIVE || item.categoryStatus != CategoryStatus.ACTIVE ->
            CartItemIssue.PRODUCT_UNAVAILABLE
        item.productStockQuantity!! <= 0 -> CartItemIssue.OUT_OF_STOCK
        item.cartItemQuantity!! > item.productStockQuantity!! -> CartItemIssue.INSUFFICIENT_STOCK
        else -> null
    }

    private fun toResponse(items: List<CartItem>): CartResponse {
        // Each product's primary image, from one query for the whole cart.
        val images = productImageRepository.findByProductIds(items.map { it.productId!! })
        val lines = items.map { item ->
            val issue = issueOf(item)
            CartItemResponse(
                productId = item.productId!!,
                name = item.productName!!,
                imageUrl = images[item.productId]?.firstOrNull { it.productImageIsPrimary!! }?.productImageUrl,
                price = item.productPrice!!,
                discountPrice = item.productDiscountPrice,
                unitPrice = item.productSellingPrice!!,
                quantity = item.cartItemQuantity!!,
                lineTotal = lineTotal(item),
                available = issue == null,
                issue = issue?.name,
                availableQuantity = if (issue == CartItemIssue.INSUFFICIENT_STOCK) item.productStockQuantity else null,
            )
        }
        val buyable = lines.filter { it.available }
        return CartResponse(
            items = lines,
            itemCount = buyable.sumOf { it.quantity },
            subtotal = buyable.fold(BigDecimal.ZERO.setScale(2)) { sum, line -> sum + line.lineTotal },
            canCheckout = lines.isNotEmpty() && lines.all { it.available },
        )
    }

    /** Selling price (discount if any) x quantity, from database prices only. */
    fun lineTotal(item: CartItem): BigDecimal = item.productSellingPrice!! * BigDecimal.valueOf(item.cartItemQuantity!!.toLong())

    // D2 (1.3): the count is shown so the customer can fix their cart.
    private fun checkStock(stock: Int, wanted: Long) {
        if (stock <= 0) throw ConflictException("Out of stock")
        if (wanted > stock) throw ConflictException("Only $stock available")
    }

    private fun itemNotInCart() = NotFoundException("Item not in cart")
}
