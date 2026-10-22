package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.Payment
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
            paymentReference = payment.reference,
            orderReference = orderReference,
            amount = payment.amount,
            currency = payment.currency,
            status = payment.status.name,
            authorizationUrl = payment.authorizationUrl
                ?: throw IllegalStateException("Payment ${payment.reference} has no authorization URL"),
        )
    }
}
