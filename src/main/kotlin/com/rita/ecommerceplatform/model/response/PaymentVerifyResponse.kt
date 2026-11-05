package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Order
import com.rita.ecommerceplatform.model.entity.Payment
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
            paymentReference = payment.paymentReference!!,
            orderReference = order.orderReference!!,
            paymentStatus = payment.paymentStatus!!.name,
            orderStatus = order.orderStatus!!.name,
            orderPaymentStatus = order.orderPaymentStatus!!.name,
            amount = payment.paymentAmount!!,
            currency = payment.paymentCurrency!!,
            paidAt = payment.paymentPaidAt,
        )
    }
}
