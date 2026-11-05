package com.rita.ecommerceplatform.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** One row of public.ec_order. The address and totals are a snapshot taken at checkout. */
data class Order(
    val orderId: Long,
    val reference: String,
    val idempotencyKey: UUID,
    val customerId: Long,
    val customerAddressId: Long?,
    val deliveryMethodId: Int,
    val couponId: Int?,
    val deliveryAddress: AddressDetails,
    val subtotal: BigDecimal,
    val deliveryFee: BigDecimal,
    val discountAmount: BigDecimal,
    val total: BigDecimal,
    val status: OrderStatus,
    val paymentStatus: OrderPaymentStatus,
    val paidAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** One row of public.ec_order_item: the product's name, SKU and price as they were at checkout. */
data class OrderItem(
    val orderItemId: Long,
    val orderId: Long,
    val productId: Long,
    val productName: String,
    val productSku: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
    val lineTotal: BigDecimal,
    val createdAt: LocalDateTime,
)

/**
 * One row of public.ec_order_status_history. At most one actor is set; neither means SYSTEM.
 * Only admin views show the actor (customers are not shown who made a change).
 */
data class OrderStatusChange(
    val fromStatus: OrderStatus?,
    val toStatus: OrderStatus,
    val note: String?,
    val createdAt: LocalDateTime,
    val changedByAdminId: Int? = null,
    val changedByCustomerId: Long? = null,
)

/** Allowed values of ec_order.orderstatus (ck__ec_order__status). The allowed changes between them come in Step 4. */
enum class OrderStatus { PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED, REFUNDED }

/** Allowed values of ec_order.orderpaymentstatus (ck__ec_order__paymentstatus). */
enum class OrderPaymentStatus { UNPAID, PAID, REFUNDED }

/** One row of public.ec_delivery_method. Orders keep their own copy of the fee (orderdeliveryfee). */
data class DeliveryMethod(
    val deliveryMethodId: Int,
    val code: String,
    val name: String,
    val description: String?,
    val fee: BigDecimal,
    val estimatedMinDays: Int?,
    val estimatedMaxDays: Int?,
    val status: DeliveryMethodStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Allowed values of ec_delivery_method.deliverymethodstatus (ck__ec_deliverymethod__status). */
enum class DeliveryMethodStatus { ACTIVE, INACTIVE }
