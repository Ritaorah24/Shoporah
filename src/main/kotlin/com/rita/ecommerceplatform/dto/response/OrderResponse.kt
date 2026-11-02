package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.AddressDetails
import com.rita.ecommerceplatform.model.DeliveryMethod
import com.rita.ecommerceplatform.model.Order
import com.rita.ecommerceplatform.model.OrderItem
import com.rita.ecommerceplatform.model.OrderStatusChange
import java.math.BigDecimal
import java.time.LocalDateTime

/** An order as its customer sees it. Prices and the address are the snapshot taken at checkout. */
data class OrderResponse(
    val orderId: Long,
    val reference: String,
    val status: String,
    val paymentStatus: String,
    val items: List<OrderItemResponse>,
    val subtotal: BigDecimal,
    val deliveryMethod: OrderDeliveryMethodResponse,
    // The fee as it was at checkout, even if the method's fee has changed since.
    val deliveryFee: BigDecimal,
    // The coupon used at checkout (null without one); discountAmount is what it took off the subtotal.
    val couponCode: String?,
    val discountAmount: BigDecimal,
    val total: BigDecimal,
    val deliveryAddress: DeliveryAddressResponse,
    // Every status the order has had, oldest first (H2), so the customer can track it.
    val statusHistory: List<StatusChangeResponse>,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(
            order: Order,
            items: List<OrderItem>,
            history: List<OrderStatusChange>,
            deliveryMethod: DeliveryMethod,
            couponCode: String?,
        ) = OrderResponse(
            orderId = order.orderId,
            reference = order.reference,
            status = order.status.name,
            paymentStatus = order.paymentStatus.name,
            items = items.map(OrderItemResponse::from),
            subtotal = order.subtotal,
            deliveryMethod = OrderDeliveryMethodResponse.from(deliveryMethod),
            deliveryFee = order.deliveryFee,
            couponCode = couponCode,
            discountAmount = order.discountAmount,
            total = order.total,
            deliveryAddress = DeliveryAddressResponse.from(order.deliveryAddress),
            statusHistory = history.map(StatusChangeResponse::from),
            createdAt = order.createdAt,
        )
    }
}

data class OrderItemResponse(
    val productId: Long,
    val productName: String,
    val productSku: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
    val lineTotal: BigDecimal,
) {
    companion object {
        fun from(item: OrderItem) = OrderItemResponse(
            productId = item.productId,
            productName = item.productName,
            productSku = item.productSku,
            unitPrice = item.unitPrice,
            quantity = item.quantity,
            lineTotal = item.lineTotal,
        )
    }
}

data class DeliveryAddressResponse(
    val recipientName: String,
    val recipientPhoneNumber: String,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val state: String,
    val country: String,
    val postalCode: String?,
) {
    companion object {
        fun from(details: AddressDetails) = DeliveryAddressResponse(
            recipientName = details.recipientName,
            recipientPhoneNumber = details.recipientPhoneNumber,
            addressLine1 = details.addressLine1,
            addressLine2 = details.addressLine2,
            city = details.city,
            state = details.state,
            country = details.country,
            postalCode = details.postalCode,
        )
    }
}

/** One step in the order's status timeline. Who made the change is not shown to the customer. */
data class StatusChangeResponse(
    val status: String,
    val changedAt: LocalDateTime,
    val note: String?,
) {
    companion object {
        fun from(change: OrderStatusChange) = StatusChangeResponse(
            status = change.toStatus.name,
            changedAt = change.createdAt,
            note = change.note,
        )
    }
}
