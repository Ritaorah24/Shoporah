package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.dto.request.CheckoutRequest
import com.rita.ecommerceplatform.dto.response.OrderResponse
import com.rita.ecommerceplatform.dto.response.OrderSummaryResponse
import com.rita.ecommerceplatform.dto.response.PageResponse
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.CustomerAddress
import com.rita.ecommerceplatform.model.NotificationType
import com.rita.ecommerceplatform.model.Order
import com.rita.ecommerceplatform.model.OrderStatus
import com.rita.ecommerceplatform.repository.CartRepository
import com.rita.ecommerceplatform.repository.CustomerAddressRepository
import com.rita.ecommerceplatform.repository.CustomerRepository
import com.rita.ecommerceplatform.repository.DeliveryMethodRepository
import com.rita.ecommerceplatform.repository.NewOrder
import com.rita.ecommerceplatform.repository.OrderItemRepository
import com.rita.ecommerceplatform.repository.OrderRepository
import com.rita.ecommerceplatform.repository.OrderStatusHistoryRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/** The order and whether this request created it (201) or found it from an earlier attempt with the same key (200). */
data class CheckoutResult(val order: OrderResponse, val created: Boolean)

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val statusHistoryRepository: OrderStatusHistoryRepository,
    private val cartRepository: CartRepository,
    private val cartService: CartService,
    private val addressRepository: CustomerAddressRepository,
    private val customerRepository: CustomerRepository,
    private val deliveryMethodRepository: DeliveryMethodRepository,
    private val referenceGenerator: OrderReferenceGenerator,
    private val notificationService: NotificationService,
) {

    /**
     * Turns the customer's cart into a PENDING order, all in one transaction: if any step fails,
     * nothing is saved and the cart is left as it was. Prices come from the database only.
     * Stock is not taken here; it is taken when payment is verified (1.4).
     */
    @Transactional
    fun checkout(customerId: Long, idempotencyKey: UUID, request: CheckoutRequest?): CheckoutResult {
        // O1: checkouts by the same customer run one at a time, so a retry with the same key waits
        // for the first attempt to finish and then finds its order below instead of creating another.
        customerRepository.lockById(customerId)
        orderRepository.findByIdempotencyKey(customerId, idempotencyKey)?.let {
            return CheckoutResult(toResponse(it), created = false)
        }

        val cartId = cartRepository.findCartId(customerId)
        val items = cartId?.let { cartRepository.findItems(it) }.orEmpty()
        if (items.isEmpty()) throw BadRequestException("Cart is empty")

        val address = deliveryAddress(customerId, request?.addressId)

        // Same rules as the cart view (CartService.issueOf), so the cart and checkout never disagree.
        val problems = items.mapNotNull { item -> cartService.issueOf(item)?.let { "${item.productName} ($it)" } }
        if (problems.isNotEmpty()) {
            throw ConflictException("Some items cannot be ordered: ${problems.joinToString(", ")}")
        }

        val deliveryMethod = deliveryMethodRepository.findActiveByCode(DEFAULT_DELIVERY_METHOD)
            ?: throw IllegalStateException("Delivery method $DEFAULT_DELIVERY_METHOD is missing (seeded by V8)")
        val subtotal = items.fold(BigDecimal.ZERO.setScale(2)) { sum, item -> sum + cartService.lineTotal(item) }
        // Coupons arrive in V2.
        val discount = BigDecimal.ZERO.setScale(2)
        val total = subtotal + deliveryMethod.fee - discount

        val orderId = try {
            orderRepository.insert(
                NewOrder(
                    reference = newReference(),
                    idempotencyKey = idempotencyKey,
                    customerId = customerId,
                    customerAddressId = address.addressId,
                    deliveryMethodId = deliveryMethod.deliveryMethodId,
                    deliveryAddress = address.details,
                    subtotal = subtotal,
                    deliveryFee = deliveryMethod.fee,
                    discountAmount = discount,
                    total = total,
                ),
            )
        } catch (e: DuplicateKeyException) {
            // Last safety net behind the lock: uq__ec_order__customerid_idempotencykey never allows a duplicate.
            throw ConflictException("This Idempotency-Key was already used for another order")
        }

        // Snapshot of each product as it is now, so later product edits never change the order.
        items.forEach { item ->
            orderItemRepository.insert(orderId, item.productId, item.productName, item.productSku, item.sellingPrice, item.quantity)
        }
        statusHistoryRepository.insert(
            orderId = orderId,
            fromStatus = null,
            toStatus = OrderStatus.PENDING,
            adminId = null,
            customerId = customerId,
            note = "Order placed",
        )
        cartRepository.deleteAllItems(cartId!!)
        cartRepository.touch(cartId)

        // Only here, on the path that creates the order: a retry with the same key returned above, without an email.
        val order = orderRepository.findById(orderId, customerId)!!
        val customer = customerRepository.findById(customerId)!!
        notificationService.queueToCustomer(
            NotificationType.ORDER_CONFIRMATION,
            customer,
            NotificationMessages.orderPlaced(customer.firstName, order, orderItemRepository.findByOrderId(orderId)),
            orderId = orderId,
        )

        return CheckoutResult(toResponse(order), created = true)
    }

    /** The customer's orders, newest first, as summaries (H1). Paging is capped like the product list (D6). */
    fun list(customerId: Long, page: Int, size: Int): PageResponse<OrderSummaryResponse> {
        val safePage = Paging.page(page)
        val safeSize = Paging.size(size)
        val orders = orderRepository.findByCustomer(customerId, safeSize, Paging.offset(safePage, safeSize))
        // One grouped query for the whole page, not one per order.
        val quantities = orderItemRepository.quantitiesByOrderIds(orders.map { it.orderId })
        return PageResponse.of(
            orders.map { OrderSummaryResponse.from(it, quantities[it.orderId] ?: 0) },
            safePage,
            safeSize,
            orderRepository.countByCustomer(customerId),
        )
    }

    /** One of the customer's own orders; another customer's order is "not found" too. */
    fun get(customerId: Long, orderId: Long): OrderResponse =
        toResponse(orderRepository.findById(orderId, customerId) ?: throw NotFoundException("Order not found"))

    /** The chosen address (must be the customer's own), or the default when none is chosen. */
    private fun deliveryAddress(customerId: Long, addressId: Long?): CustomerAddress =
        if (addressId != null) {
            addressRepository.findById(addressId, customerId) ?: throw BadRequestException("Address $addressId does not exist")
        } else {
            addressRepository.findDefault(customerId)
                ?: throw BadRequestException("No delivery address: send addressId or set a default address")
        }

    // O3: about a trillion combinations per day, so a clash is very unlikely; a few attempts are plenty.
    private fun newReference(): String {
        repeat(REFERENCE_ATTEMPTS) {
            val reference = referenceGenerator.generate()
            if (!orderRepository.existsByReference(reference)) return reference
        }
        throw IllegalStateException("Could not generate a unique order reference")
    }

    private fun toResponse(order: Order): OrderResponse =
        OrderResponse.from(
            order,
            orderItemRepository.findByOrderId(order.orderId),
            statusHistoryRepository.findByOrderId(order.orderId),
        )

    companion object {
        // Until delivery methods arrive in V2, every order uses the seeded STANDARD method.
        const val DEFAULT_DELIVERY_METHOD = "STANDARD"
        private const val REFERENCE_ATTEMPTS = 5
    }
}
