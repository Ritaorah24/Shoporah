package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.Order
import com.rita.ecommerceplatform.model.Payment
import java.math.BigDecimal
import java.time.LocalDateTime

/** The verified state of a payment and its order. paymentStatus PENDING means the customer has not finished paying yet. */
data class PaymentVerifyResponse(
    val paymentReference: String,
    val orderReference: String,
    val paymentStatus: String,
    val orderStatus: String,
    val orderPaymentStatus: String,
    // In naira, as on the order.
    val amount: BigDecimal,
    val currency: String,
    val paidAt: LocalDateTime?,
) {
    companion object {
        fun from(payment: Payment, order: Order) = PaymentVerifyResponse(
            paymentReference = payment.reference,
            orderReference = order.reference,
            paymentStatus = payment.status.name,
            orderStatus = order.status.name,
            orderPaymentStatus = order.paymentStatus.name,
            amount = payment.amount,
            currency = payment.currency,
            paidAt = payment.paidAt,
        )
    }
}
