package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.AccessNotAllowedException
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.model.entity.OrderStatus
import com.rita.ecommerceplatform.model.entity.OrderStatus.CANCELLED
import com.rita.ecommerceplatform.model.entity.OrderStatus.CONFIRMED
import com.rita.ecommerceplatform.model.entity.OrderStatus.DELIVERED
import com.rita.ecommerceplatform.model.entity.OrderStatus.PENDING
import com.rita.ecommerceplatform.model.entity.OrderStatus.PROCESSING
import com.rita.ecommerceplatform.model.entity.OrderStatus.REFUNDED
import com.rita.ecommerceplatform.model.entity.OrderStatus.SHIPPED

/** Who makes a status change. SYSTEM covers automatic changes, e.g. a verified payment. */
enum class OrderStatusActor { CUSTOMER, ADMIN, SYSTEM }

/**
 * The only place that decides which order status changes are allowed, and by whom (S1).
 * Every status change must call ensureCanChange first. CANCELLED and REFUNDED are final.
 */
object OrderStatusRules {

    private val allowed: Map<Pair<OrderStatus, OrderStatus>, Set<OrderStatusActor>> = mapOf(
        // Only a verified payment confirms an order, so an admin cannot confirm an unpaid order by hand.
        (PENDING to CONFIRMED) to setOf(OrderStatusActor.SYSTEM),
        // Customers may cancel only before paying. SYSTEM cancels a paid order whose stock ran out (refund needed).
        (PENDING to CANCELLED) to setOf(OrderStatusActor.CUSTOMER, OrderStatusActor.ADMIN, OrderStatusActor.SYSTEM),
        (CONFIRMED to PROCESSING) to setOf(OrderStatusActor.ADMIN),
        (CONFIRMED to CANCELLED) to setOf(OrderStatusActor.ADMIN),
        // Still in the warehouse, so the stock can go back; from SHIPPED on, a refund is the way out.
        (PROCESSING to CANCELLED) to setOf(OrderStatusActor.ADMIN),
        (PROCESSING to SHIPPED) to setOf(OrderStatusActor.ADMIN),
        (SHIPPED to DELIVERED) to setOf(OrderStatusActor.ADMIN),
        (DELIVERED to REFUNDED) to setOf(OrderStatusActor.ADMIN),
    )

    /**
     * Throws 409 if the change is not in the table (including "changing" to the same status),
     * or 403 if it is but this actor may not make it.
     */
    fun ensureCanChange(from: OrderStatus, to: OrderStatus, actor: OrderStatusActor) {
        val actors = allowed[from to to] ?: throw BadRequestException("Order status cannot change from $from to $to")
        if (actor !in actors) throw AccessNotAllowedException("$actor cannot change an order from $from to $to")
    }

    /** The statuses this actor may move the order to next, in lifecycle order (empty for final statuses). */
    fun nextStatuses(from: OrderStatus, actor: OrderStatusActor): List<OrderStatus> =
        OrderStatus.entries.filter { to -> actor in allowed[from to to].orEmpty() }
}
