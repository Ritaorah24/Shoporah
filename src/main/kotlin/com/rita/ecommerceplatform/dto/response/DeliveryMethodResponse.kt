package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.DeliveryMethod
import java.math.BigDecimal
import java.time.LocalDateTime

/** A delivery method as shoppers see it: what to send as deliveryMethodCode at checkout, and its fee. */
data class DeliveryMethodResponse(
    val code: String,
    val name: String,
    val description: String?,
    val fee: BigDecimal,
    val estimatedMinDays: Int?,
    val estimatedMaxDays: Int?,
) {
    companion object {
        fun from(method: DeliveryMethod) = DeliveryMethodResponse(
            code = method.code,
            name = method.name,
            description = method.description,
            fee = method.fee,
            estimatedMinDays = method.estimatedMinDays,
            estimatedMaxDays = method.estimatedMaxDays,
        )
    }
}

/** A delivery method as admins see it: everything, including its id and status. */
data class AdminDeliveryMethodResponse(
    val deliveryMethodId: Int,
    val code: String,
    val name: String,
    val description: String?,
    val fee: BigDecimal,
    val estimatedMinDays: Int?,
    val estimatedMaxDays: Int?,
    val status: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(method: DeliveryMethod) = AdminDeliveryMethodResponse(
            deliveryMethodId = method.deliveryMethodId,
            code = method.code,
            name = method.name,
            description = method.description,
            fee = method.fee,
            estimatedMinDays = method.estimatedMinDays,
            estimatedMaxDays = method.estimatedMaxDays,
            status = method.status.name,
            createdAt = method.createdAt,
            updatedAt = method.updatedAt,
        )
    }
}

/** Which delivery method an order used, as shown on the order. */
data class OrderDeliveryMethodResponse(val code: String, val name: String) {
    companion object {
        fun from(method: DeliveryMethod) = OrderDeliveryMethodResponse(method.code, method.name)
    }
}
