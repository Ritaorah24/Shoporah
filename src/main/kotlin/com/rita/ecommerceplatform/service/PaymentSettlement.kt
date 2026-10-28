package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.model.InventoryMovementType
import com.rita.ecommerceplatform.model.NotificationType
import com.rita.ecommerceplatform.model.Order
import com.rita.ecommerceplatform.model.OrderPaymentStatus
import com.rita.ecommerceplatform.model.OrderStatus
import com.rita.ecommerceplatform.model.Payment
import com.rita.ecommerceplatform.model.PaymentStatus
import com.rita.ecommerceplatform.repository.CustomerRepository
import com.rita.ecommerceplatform.repository.InventoryMovementRepository
import com.rita.ecommerceplatform.repository.OrderItemRepository
import com.rita.ecommerceplatform.repository.OrderRepository
import com.rita.ecommerceplatform.repository.OrderStatusHistoryRepository
import com.rita.ecommerceplatform.repository.PaymentRepository
import com.rita.ecommerceplatform.repository.ProductRepository
import com.rita.ecommerceplatform.repository.VerifiedDetails
import com.rita.ecommerceplatform.repository.api.GatewayPaymentStatus
import com.rita.ecommerceplatform.repository.api.GatewayVerifyResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Applies what the provider says about a payment, exactly once. Shared by verify (Step 3) and the webhook
 * (Step 5), so both paths follow the same rules. It is its own bean so that @Transactional works: calls
 * from PaymentService go through Spring's proxy (a call inside one class would bypass it).
 *
 * Locks are always taken order → payment → products (by productId), the same order as initialize,
 * so two settles never wait on each other forever (a deadlock).
 */
@Service
class PaymentSettlement(
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val productRepository: ProductRepository,
    private val inventoryMovementRepository: InventoryMovementRepository,
    private val statusHistoryRepository: OrderStatusHistoryRepository,
    private val customerRepository: CustomerRepository,
    private val notificationService: NotificationService,
) {

    private val log = LoggerFactory.getLogger(PaymentSettlement::class.java)

    /**
     * Settles a payment with the provider's answer (null when the provider does not know the reference) and
     * returns it as now stored. Whoever comes second (verify or webhook) finds it no longer PENDING and changes
     * nothing, so a payment is never processed twice and stock is never taken twice.
     */
    @Transactional
    fun settle(paymentId: Long, result: GatewayVerifyResult?): Payment {
        // Read without a lock only to learn the order; a payment's order never changes.
        val orderId = paymentRepository.findById(paymentId)?.orderId ?: throw IllegalStateException("Payment $paymentId does not exist")
        val order = orderRepository.lockById(orderId)!!
        val payment = paymentRepository.lockById(paymentId)!!
        if (payment.status != PaymentStatus.PENDING) return payment

        if (result == null) {
            // Can happen for a moment right after initialize; the customer can verify again later.
            log.warn("Payment {}: the provider does not know this reference yet; left PENDING", payment.reference)
            return payment
        }

        val details = VerifiedDetails(result.transactionId, result.channel, result.gatewayResponse, result.rawResponse)
        when (result.status) {
            GatewayPaymentStatus.PENDING -> return payment
            GatewayPaymentStatus.FAILED -> paymentRepository.markUnsuccessful(paymentId, PaymentStatus.FAILED, details)
            GatewayPaymentStatus.ABANDONED -> paymentRepository.markUnsuccessful(paymentId, PaymentStatus.ABANDONED, details)
            GatewayPaymentStatus.SUCCESS -> succeed(order, payment, result, details)
        }
        return paymentRepository.findById(paymentId)!!
    }

    private fun succeed(order: Order, payment: Payment, result: GatewayVerifyResult, details: VerifiedDetails) {
        // Never trust a "success" for a different payment, amount or currency (e.g. a cheaper payment's reference).
        val expectedKobo = PaymentService.toKobo(payment.amount)
        if (!result.reference.equals(payment.reference, ignoreCase = true) ||
            result.amountKobo != expectedKobo ||
            !result.currency.equals(payment.currency, ignoreCase = true)
        ) {
            log.error(
                "Payment {} succeeded at the provider but does not match: paid {} {} kobo (reference {}), expected {} {} kobo; refund needed",
                payment.reference, result.currency, result.amountKobo, result.reference, payment.currency, expectedKobo,
            )
            paymentRepository.markUnsuccessful(payment.paymentId, PaymentStatus.FAILED, details, MISMATCH)
            return
        }

        // Money was taken, but the order can no longer take it (another attempt already paid it).
        if (order.status != OrderStatus.PENDING || order.paymentStatus != OrderPaymentStatus.UNPAID) {
            val note = if (order.paymentStatus == OrderPaymentStatus.PAID) {
                "Duplicate payment: order already paid by ${paymentRepository.findSuccessfulForOrder(order.orderId)?.reference}, refund needed"
            } else {
                "Order was ${order.status} when the payment succeeded, refund needed"
            }
            log.error("Payment {} for order {}: {}", payment.reference, order.reference, note)
            paymentRepository.markUnsuccessful(payment.paymentId, PaymentStatus.FAILED, details, note)
            return
        }

        // The payment really happened, whatever happens to the order next.
        val paidAt = result.paidAt?.let(::toPlatformTime) ?: LocalDateTime.now(PLATFORM_OFFSET)
        paymentRepository.markSucceeded(payment.paymentId, details, paidAt)

        // Lock every product first (by productId) and check them all, so stock is taken for all items or none.
        val items = orderItemRepository.findByOrderId(order.orderId).sortedBy { it.productId }
        val shortItems = items.filter { item ->
            val product = productRepository.findByIdForUpdate(item.productId)
                ?: throw IllegalStateException("Product ${item.productId} of order ${order.reference} does not exist")
            product.stockQuantity < item.quantity
        }

        if (shortItems.isNotEmpty()) {
            // The 1.3 race: another order took the last units first. Record the payment and cancel, so the refund is visible.
            val note = "Out of stock after payment, refund needed: ${shortItems.joinToString(", ") { it.productName }}"
            log.error("Order {} paid by {}: {}", order.reference, payment.reference, note)
            changeStatus(order, OrderStatus.CANCELLED, paidAt, note)
            return
        }

        items.forEach { item ->
            // Cannot be null under the locks above; if it ever is, throwing rolls the whole settle back.
            val stockAfter = productRepository.decreaseStock(item.productId, item.quantity)
                ?: throw IllegalStateException("Stock of product ${item.productId} changed while locked")
            inventoryMovementRepository.insert(
                productId = item.productId,
                type = InventoryMovementType.SALE,
                quantityChange = -item.quantity,
                stockAfter = stockAfter,
                adminId = null,
                note = "Order ${order.reference}",
                orderId = order.orderId,
            )
        }
        changeStatus(order, OrderStatus.CONFIRMED, paidAt, "Payment verified")

        // Reached once per order: changeStatus only succeeds while the order is still PENDING and UNPAID.
        val customer = customerRepository.findById(order.customerId)
            ?: throw IllegalStateException("Customer ${order.customerId} of order ${order.reference} does not exist")
        notificationService.queueToCustomer(
            NotificationType.PAYMENT_CONFIRMATION,
            customer,
            NotificationMessages.paymentConfirmed(customer.firstName, order),
            orderId = order.orderId,
        )
    }

    private fun changeStatus(order: Order, to: OrderStatus, paidAt: LocalDateTime, note: String) {
        OrderStatusRules.ensureCanChange(order.status, to, OrderStatusActor.SYSTEM)
        check(orderRepository.markPaid(order.orderId, to, paidAt) == 1) { "Order ${order.reference} was no longer PENDING and UNPAID" }
        statusHistoryRepository.insert(order.orderId, order.status, to, adminId = null, customerId = null, note = note)
    }

    companion object {
        const val MISMATCH = "Amount or currency does not match the order"

        // The platform's time convention (UTC+1), the same as SQL_NOW and the column defaults.
        val PLATFORM_OFFSET: ZoneOffset = ZoneOffset.ofHours(1)

        /** The provider's time (Paystack sends UTC) in the platform's convention. */
        fun toPlatformTime(time: OffsetDateTime): LocalDateTime = time.atZoneSameInstant(PLATFORM_OFFSET).toLocalDateTime()
    }
}
