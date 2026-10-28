package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.dto.request.CancelOrderRequest
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
import com.rita.ecommerceplatform.model.OrderPaymentStatus
import com.rita.ecommerceplatform.model.OrderStatus
import com.rita.ecommerceplatform.model.PaymentStatus
import com.rita.ecommerceplatform.repository.CartRepository
import com.rita.ecommerceplatform.repository.CustomerAddressRepository
import com.rita.ecommerceplatform.repository.CustomerRepository
import com.rita.ecommerceplatform.repository.DeliveryMethodRepository
import com.rita.ecommerceplatform.repository.NewOrder
import com.rita.ecommerceplatform.repository.OrderItemRepository
import com.rita.ecommerceplatform.repository.OrderRepository
import com.rita.ecommerceplatform.repository.OrderStatusHistoryRepository
import com.rita.ecommerceplatform.repository.PaymentRepository
import com.rita.ecommerceplatform.repository.api.PaymentGateway
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
    private val paymentRepository: PaymentRepository,
    private val paymentGateway: PaymentGateway,
    private val paymentSettlement: PaymentSettlement,
    private val orderCancellation: OrderCancellation,
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

        // The fee is read here, inside the transaction, and copied onto the order: later fee changes never affect it.
        val deliveryMethodCode = request?.deliveryMethodCode?.ifEmpty { null } ?: DEFAULT_DELIVERY_METHOD
        val deliveryMethod = deliveryMethodRepository.findActiveByCode(deliveryMethodCode)
            ?: throw BadRequestException("Delivery method ${deliveryMethodCode.uppercase()} is not available")
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

    /**
     * The customer cancels one of their own unpaid PENDING orders. If a payment page is open, the provider is
     * asked first (outside any transaction, like verify): a payment made just before the cancel then confirms the
     * order (409 here) instead of leaving a paid, cancelled order. If the provider cannot be reached, nothing changes (502).
     */
    fun cancel(customerId: Long, orderId: Long, request: CancelOrderRequest?): OrderResponse {
        val order = orderRepository.findById(orderId, customerId) ?: throw NotFoundException("Order not found")
        if (order.status != OrderStatus.PENDING || order.paymentStatus != OrderPaymentStatus.UNPAID) {
            throw OrderCancellation.notCancellable()
        }

        paymentRepository.findOpenAttempt(orderId)?.takeIf { it.authorizationUrl != null }?.let { open ->
            val settled = paymentSettlement.settle(open.paymentId, paymentGateway.verify(open.reference))
            if (settled.status == PaymentStatus.SUCCESS) {
                throw ConflictException("This order has already been paid and can no longer be cancelled")
            }
        }

        orderCancellation.cancelByCustomer(customerId, orderId, request?.reason?.trim()?.ifEmpty { null })
        return get(customerId, orderId)
    }

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
            deliveryMethodRepository.findById(order.deliveryMethodId)!!,
        )

    companion object {
        // Used when checkout names no delivery method (seeded by V8; DeliveryMethodService never lets it be deactivated).
        const val DEFAULT_DELIVERY_METHOD = "STANDARD"
        private const val REFERENCE_ATTEMPTS = 5
    }
}
