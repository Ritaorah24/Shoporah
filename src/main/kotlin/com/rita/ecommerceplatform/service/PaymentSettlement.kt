package com.rita.ecommerceplatform.service

import io.github.oshai.kotlinlogging.KotlinLogging
import com.rita.ecommerceplatform.model.entity.InventoryMovementType
import com.rita.ecommerceplatform.model.entity.NotificationType
import com.rita.ecommerceplatform.model.entity.Order
import com.rita.ecommerceplatform.model.entity.OrderPaymentStatus
import com.rita.ecommerceplatform.model.entity.OrderStatus
import com.rita.ecommerceplatform.model.entity.Payment
import com.rita.ecommerceplatform.model.entity.PaymentStatus
import com.rita.ecommerceplatform.repository.database.CustomerRepository
import com.rita.ecommerceplatform.repository.database.InventoryMovementRepository
import com.rita.ecommerceplatform.repository.database.OrderItemRepository
import com.rita.ecommerceplatform.repository.database.OrderRepository
import com.rita.ecommerceplatform.repository.database.OrderStatusHistoryRepository
import com.rita.ecommerceplatform.repository.database.PaymentRepository
import com.rita.ecommerceplatform.repository.database.ProductRepository
import com.rita.ecommerceplatform.repository.database.VerifiedDetails
import com.rita.ecommerceplatform.repository.api.GatewayPaymentStatus
import com.rita.ecommerceplatform.repository.api.GatewayVerifyResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

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

    /**
     * Settles a payment with the provider's answer (null when the provider does not know the reference) and
     * returns it as now stored. Whoever comes second (verify or webhook) finds it no longer PENDING and changes
     * nothing, so a payment is never processed twice and stock is never taken twice.
     */
    @Transactional
    fun settle(paymentId: Long, result: GatewayVerifyResult?): Payment {
        // Read without a lock only to learn the order; a payment's order never changes.
        val orderId = paymentRepository.findById(paymentId)?.paymentOrderId ?: throw IllegalStateException("Payment $paymentId does not exist")
        val order = orderRepository.lockById(orderId)!!
        val payment = paymentRepository.lockById(paymentId)!!
        if (payment.paymentStatus != PaymentStatus.PENDING) return payment

        if (result == null) {
            // Can happen for a moment right after initialize; the customer can verify again later.
            logger.warn { "Payment ${payment.paymentReference}: the provider does not know this reference yet; left PENDING" }
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
        val expectedKobo = PaymentService.toKobo(payment.paymentAmount!!)
        if (!result.reference.equals(payment.paymentReference, ignoreCase = true) ||
            result.amountKobo != expectedKobo ||
            !result.currency.equals(payment.paymentCurrency, ignoreCase = true)
        ) {
            logger.error { "Payment ${payment.paymentReference} succeeded at the provider but does not match: paid ${result.currency} ${result.amountKobo} kobo (reference ${result.reference}), expected ${payment.paymentCurrency} $expectedKobo kobo; refund needed" }
            paymentRepository.markUnsuccessful(payment.paymentId!!, PaymentStatus.FAILED, details, MISMATCH)
            return
        }

        // Money was taken, but the order can no longer take it (another attempt already paid it).
        if (order.orderStatus != OrderStatus.PENDING || order.orderPaymentStatus != OrderPaymentStatus.UNPAID) {
            val note = if (order.orderPaymentStatus == OrderPaymentStatus.PAID) {
                "Duplicate payment: order already paid by ${paymentRepository.findSuccessfulForOrder(order.orderId!!)?.paymentReference}, refund needed"
            } else {
                "Order was ${order.orderStatus} when the payment succeeded, refund needed"
            }
            logger.error { "Payment ${payment.paymentReference} for order ${order.orderReference}: $note" }
            paymentRepository.markUnsuccessful(payment.paymentId!!, PaymentStatus.FAILED, details, note)
            return
        }

        // The payment really happened, whatever happens to the order next.
        val paidAt = result.paidAt?.let(::toPlatformTime) ?: LocalDateTime.now(PLATFORM_OFFSET)
        paymentRepository.markSucceeded(payment.paymentId!!, details, paidAt)

        // Lock every product first (by productId) and check them all, so stock is taken for all items or none.
        val items = orderItemRepository.findByOrderId(order.orderId!!).sortedBy { it.orderItemProductId }
        val shortItems = items.filter { item ->
            val product = productRepository.findByIdForUpdate(item.orderItemProductId!!)
                ?: throw IllegalStateException("Product ${item.orderItemProductId} of order ${order.orderReference} does not exist")
            product.productStockQuantity!! < item.orderItemQuantity!!
        }

        if (shortItems.isNotEmpty()) {
            // The 1.3 race: another order took the last units first. Record the payment and cancel, so the refund is visible.
            val note = "Out of stock after payment, refund needed: ${shortItems.joinToString(", ") { it.orderItemProductName!! }}"
            logger.error { "Order ${order.orderReference} paid by ${payment.paymentReference}: $note" }
            changeStatus(order, OrderStatus.CANCELLED, paidAt, note)
            notifyCustomer(order, NotificationType.ORDER_CANCELLED) { firstName ->
                NotificationMessages.orderCancelled(
                    firstName, order,
                    reason = "Sold out before your payment was confirmed: ${shortItems.joinToString(", ") { it.orderItemProductName!! }}",
                    paid = true,
                )
            }
            return
        }

        items.forEach { item ->
            // Cannot be null under the locks above; if it ever is, throwing rolls the whole settle back.
            val stockAfter = productRepository.decreaseStock(item.orderItemProductId!!, item.orderItemQuantity!!)
                ?: throw IllegalStateException("Stock of product ${item.orderItemProductId} changed while locked")
            inventoryMovementRepository.insert(
                productId = item.orderItemProductId!!,
                type = InventoryMovementType.SALE,
                quantityChange = -item.orderItemQuantity!!,
                stockAfter = stockAfter,
                adminId = null,
                note = "Order ${order.orderReference}",
                orderId = order.orderId,
            )
        }
        changeStatus(order, OrderStatus.CONFIRMED, paidAt, "Payment verified")

        // Reached once per order: changeStatus only succeeds while the order is still PENDING and UNPAID.
        notifyCustomer(order, NotificationType.PAYMENT_CONFIRMATION) { firstName ->
            NotificationMessages.paymentConfirmed(firstName, order)
        }
    }

    // `message` gets the customer's first name.
    private fun notifyCustomer(order: Order, type: NotificationType, message: (String) -> EmailMessage) {
        val customer = customerRepository.findById(order.orderCustomerId!!)
            ?: throw IllegalStateException("Customer ${order.orderCustomerId} of order ${order.orderReference} does not exist")
        notificationService.queueToCustomer(type, customer, message(customer.customerFirstName!!), orderId = order.orderId)
    }

    private fun changeStatus(order: Order, to: OrderStatus, paidAt: LocalDateTime, note: String) {
        OrderStatusRules.ensureCanChange(order.orderStatus!!, to, OrderStatusActor.SYSTEM)
        check(orderRepository.markPaid(order.orderId!!, to, paidAt) == 1) { "Order ${order.orderReference} was no longer PENDING and UNPAID" }
        statusHistoryRepository.insert(order.orderId!!, order.orderStatus, to, adminId = null, customerId = null, note = note)
    }

    companion object {
        const val MISMATCH = "Amount or currency does not match the order"

        // The platform's time convention (UTC+1), the same as SQL_NOW and the column defaults.
        val PLATFORM_OFFSET: ZoneOffset = ZoneOffset.ofHours(1)

        /** The provider's time (Paystack sends UTC) in the platform's convention. */
        fun toPlatformTime(time: OffsetDateTime): LocalDateTime = time.atZoneSameInstant(PLATFORM_OFFSET).toLocalDateTime()
    }
}
