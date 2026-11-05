package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Payment
import java.math.BigDecimal

/** A started payment: the customer opens authorizationUrl to pay on the provider's page. */
data class PaymentInitResponse(
    val paymentReference: String,
    val orderReference: String,
    // In naira, as on the order.
    val amount: BigDecimal,
    val currency: String,
    val status: String,
    val authorizationUrl: String,
) {
    companion object {
        fun from(payment: Payment, orderReference: String) = PaymentInitResponse(
            paymentReference = payment.paymentReference!!,
            orderReference = orderReference,
            amount = payment.paymentAmount!!,
            currency = payment.paymentCurrency!!,
            status = payment.paymentStatus!!.name,
            authorizationUrl = payment.paymentAuthorizationUrl
                ?: throw IllegalStateException("Payment ${payment.paymentReference} has no authorization URL"),
        )
    }
}
