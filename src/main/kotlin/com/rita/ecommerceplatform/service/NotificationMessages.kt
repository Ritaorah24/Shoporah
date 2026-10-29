package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.model.Order
import com.rita.ecommerceplatform.model.OrderItem
import java.math.BigDecimal
import java.util.Locale

data class EmailMessage(val subject: String, val body: String)

/** The wording of every lifecycle email (plain text), kept in one place so it is easy to change. */
object NotificationMessages {

    fun registration(firstName: String) = EmailMessage(
        subject = "Welcome to Shoporah",
        body = """
            Hi $firstName,

            Your Shoporah account is ready. You can now browse products, fill your cart and place orders.

            Happy shopping!
        """.trimIndent(),
    )

    fun orderPlaced(firstName: String, order: Order, items: List<OrderItem>) = EmailMessage(
        subject = "We received your order ${order.reference}",
        body = buildString {
            appendLine("Hi $firstName,")
            appendLine()
            appendLine("Thank you for your order ${order.reference}.")
            appendLine()
            items.forEach { appendLine("- ${it.productName} x ${it.quantity}: ${naira(it.lineTotal)}") }
            appendLine()
            appendLine("Subtotal: ${naira(order.subtotal)}")
            appendLine("Delivery: ${naira(order.deliveryFee)}")
            if (order.discountAmount.signum() > 0) appendLine("Discount: -${naira(order.discountAmount)}")
            appendLine("Total: ${naira(order.total)}")
            appendLine()
            append("Your order is waiting for payment. We will let you know as soon as the payment is confirmed.")
        },
    )

    fun paymentConfirmed(firstName: String, order: Order) = EmailMessage(
        subject = "Payment received for order ${order.reference}",
        body = """
            Hi $firstName,

            We received your payment of ${naira(order.total)} for order ${order.reference}.
            Your order is confirmed and we are getting it ready.
        """.trimIndent(),
    )

    fun orderShipped(firstName: String, reference: String, note: String?) = EmailMessage(
        subject = "Your order $reference is on its way",
        body = buildString {
            appendLine("Hi $firstName,")
            appendLine()
            appendLine("Good news: your order $reference has been shipped.")
            if (note != null) appendLine("Details: $note")
            appendLine()
            append("We will let you know when it is delivered.")
        },
    )

    fun orderDelivered(firstName: String, reference: String) = EmailMessage(
        subject = "Your order $reference was delivered",
        body = """
            Hi $firstName,

            Your order $reference has been delivered. We hope you enjoy it!
        """.trimIndent(),
    )

    // Only unpaid orders can be cancelled by the customer, so nothing was charged.
    fun orderCancelledByCustomer(firstName: String, reference: String) = EmailMessage(
        subject = "Your order $reference was cancelled",
        body = """
            Hi $firstName,

            Your order $reference was cancelled as you asked. You were not charged.
        """.trimIndent(),
    )

    /** Cancelled by an admin or by the system. `paid` decides whether the customer is told about a refund. */
    fun orderCancelled(firstName: String, order: Order, reason: String, paid: Boolean) = EmailMessage(
        subject = "Your order ${order.reference} was cancelled",
        body = buildString {
            appendLine("Hi $firstName,")
            appendLine()
            appendLine("We are sorry, your order ${order.reference} was cancelled.")
            appendLine("Reason: $reason")
            appendLine()
            append(if (paid) "Your payment of ${naira(order.total)} will be refunded." else "You were not charged.")
        },
    )

    // Sent when the provider says the money was sent back; banks can take a few more days to show it.
    fun orderRefunded(firstName: String, reference: String, amount: BigDecimal) = EmailMessage(
        subject = "Your refund for order $reference",
        body = """
            Hi $firstName,

            Your refund of ${naira(amount)} for order $reference has been processed.
            It can take a few working days to reach your account, depending on your bank.
        """.trimIndent(),
    )

    fun passwordChanged(firstName: String) = EmailMessage(
        subject = "Your Shoporah password was changed",
        body = """
            Hi $firstName,

            The password of your Shoporah account was just changed.
            If this wasn't you, reset your password now with "Forgot password" and contact us.
        """.trimIndent(),
    )

    // e.g. ₦2,400.00 (Locale.US for the comma grouping, whatever the server's locale is).
    fun naira(amount: BigDecimal): String = "₦" + String.format(Locale.US, "%,.2f", amount)
}
