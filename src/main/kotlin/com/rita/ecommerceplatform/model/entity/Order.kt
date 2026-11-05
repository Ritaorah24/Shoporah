package com.rita.ecommerceplatform.model.entity

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** One row of public.ec_order. The address and totals are a snapshot taken at checkout. */
data class Order(
    var orderId: Long? = null,
    var orderReference: String? = null,
    var orderIdempotencyKey: UUID? = null,
    var orderCustomerId: Long? = null,
    var orderCustomerAddressId: Long? = null,
    var orderDeliveryMethodId: Int? = null,
    var orderCouponId: Int? = null,
    var orderRecipientName: String? = null,
    var orderRecipientPhoneNumber: String? = null,
    var orderAddressLine1: String? = null,
    var orderAddressLine2: String? = null,
    var orderCity: String? = null,
    var orderState: String? = null,
    var orderCountry: String? = null,
    var orderPostalCode: String? = null,
    var orderSubtotal: BigDecimal? = null,
    var orderDeliveryFee: BigDecimal? = null,
    var orderDiscountAmount: BigDecimal? = null,
    var orderTotal: BigDecimal? = null,
    var orderStatus: OrderStatus? = null,
    var orderPaymentStatus: OrderPaymentStatus? = null,
    var orderPaidAt: LocalDateTime? = null,
    var orderCreatedAt: LocalDateTime? = null,
    var orderUpdatedAt: LocalDateTime? = null,
) {
    /** The delivery details as one value. */
    val deliveryAddress: AddressDetails
        get() = AddressDetails(
            recipientName = orderRecipientName!!,
            recipientPhoneNumber = orderRecipientPhoneNumber!!,
            addressLine1 = orderAddressLine1!!,
            addressLine2 = orderAddressLine2,
            city = orderCity!!,
            state = orderState!!,
            country = orderCountry!!,
            postalCode = orderPostalCode,
        )
}

/** One row of public.ec_order_item: the product's name, SKU and price as they were at checkout. */
data class OrderItem(
    var orderItemId: Long? = null,
    var orderItemOrderId: Long? = null,
    var orderItemProductId: Long? = null,
    var orderItemProductName: String? = null,
    var orderItemProductSku: String? = null,
    var orderItemUnitPrice: BigDecimal? = null,
    var orderItemQuantity: Int? = null,
    var orderItemLineTotal: BigDecimal? = null,
    var orderItemCreatedAt: LocalDateTime? = null,
)

/**
 * One row of public.ec_order_status_history. At most one actor is set; neither means SYSTEM.
 * Only admin views show the actor (customers are not shown who made a change).
 */
data class OrderStatusChange(
    var orderStatusHistoryFromStatus: OrderStatus? = null,
    var orderStatusHistoryToStatus: OrderStatus? = null,
    var orderStatusHistoryNote: String? = null,
    var orderStatusHistoryCreatedAt: LocalDateTime? = null,
    var orderStatusHistoryChangedByAdminId: Int? = null,
    var orderStatusHistoryChangedByCustomerId: Long? = null,
)

/** Allowed values of ec_order.orderstatus (ck__ec_order__status). The allowed changes between them come in Step 4. */
enum class OrderStatus { PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED, REFUNDED }

/** Allowed values of ec_order.orderpaymentstatus (ck__ec_order__paymentstatus). */
enum class OrderPaymentStatus { UNPAID, PAID, REFUNDED }

/** One row of public.ec_delivery_method. Orders keep their own copy of the fee (orderdeliveryfee). */
data class DeliveryMethod(
    var deliveryMethodId: Int? = null,
    var deliveryMethodCode: String? = null,
    var deliveryMethodName: String? = null,
    var deliveryMethodDescription: String? = null,
    var deliveryMethodFee: BigDecimal? = null,
    var deliveryMethodEstimatedMinDays: Int? = null,
    var deliveryMethodEstimatedMaxDays: Int? = null,
    var deliveryMethodStatus: DeliveryMethodStatus? = null,
    var deliveryMethodCreatedAt: LocalDateTime? = null,
    var deliveryMethodUpdatedAt: LocalDateTime? = null,
)

/** Allowed values of ec_delivery_method.deliverymethodstatus (ck__ec_deliverymethod__status). */
enum class DeliveryMethodStatus { ACTIVE, INACTIVE }
