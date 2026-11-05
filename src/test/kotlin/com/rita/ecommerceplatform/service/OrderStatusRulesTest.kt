package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.ForbiddenException
import com.rita.ecommerceplatform.model.entity.OrderStatus
import com.rita.ecommerceplatform.model.entity.OrderStatus.CANCELLED
import com.rita.ecommerceplatform.model.entity.OrderStatus.CONFIRMED
import com.rita.ecommerceplatform.model.entity.OrderStatus.DELIVERED
import com.rita.ecommerceplatform.model.entity.OrderStatus.PENDING
import com.rita.ecommerceplatform.model.entity.OrderStatus.PROCESSING
import com.rita.ecommerceplatform.model.entity.OrderStatus.REFUNDED
import com.rita.ecommerceplatform.model.entity.OrderStatus.SHIPPED
import com.rita.ecommerceplatform.service.OrderStatusActor.ADMIN
import com.rita.ecommerceplatform.service.OrderStatusActor.CUSTOMER
import com.rita.ecommerceplatform.service.OrderStatusActor.SYSTEM
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Order status rules (checkpoint 1.3, Step 4). Plain logic: no Spring, no database. */
class OrderStatusRulesTest {

    // The expected table, written out separately from the code so a change to either is caught.
    private val expected: Map<Pair<OrderStatus, OrderStatus>, Set<OrderStatusActor>> = mapOf(
        (PENDING to CONFIRMED) to setOf(SYSTEM),
        (PENDING to CANCELLED) to setOf(CUSTOMER, ADMIN, SYSTEM),
        (CONFIRMED to PROCESSING) to setOf(ADMIN),
        (CONFIRMED to CANCELLED) to setOf(ADMIN),
        (PROCESSING to CANCELLED) to setOf(ADMIN),
        (PROCESSING to SHIPPED) to setOf(ADMIN),
        (SHIPPED to DELIVERED) to setOf(ADMIN),
        (DELIVERED to REFUNDED) to setOf(ADMIN),
    )

    @Test
    fun `every allowed change passes for its actors and is forbidden for the others`() {
        expected.forEach { (change, actors) ->
            val (from, to) = change
            OrderStatusActor.entries.forEach { actor ->
                if (actor in actors) {
                    assertDoesNotThrow("$actor $from -> $to") { OrderStatusRules.ensureCanChange(from, to, actor) }
                } else {
                    assertFailsWith<ForbiddenException>("$actor $from -> $to") {
                        OrderStatusRules.ensureCanChange(from, to, actor)
                    }
                }
            }
        }
    }

    @Test
    fun `every other pair of statuses is rejected for every actor, including the same status`() {
        var checked = 0
        OrderStatus.entries.forEach { from ->
            OrderStatus.entries.forEach { to ->
                if ((from to to) !in expected) {
                    OrderStatusActor.entries.forEach { actor ->
                        assertFailsWith<ConflictException>("$actor $from -> $to") {
                            OrderStatusRules.ensureCanChange(from, to, actor)
                        }
                    }
                    checked++
                }
            }
        }
        // 7 x 7 pairs, of which 8 are allowed.
        assertEquals(41, checked)
    }

    @Test
    fun `cancelled and refunded orders are final`() {
        listOf(CANCELLED, REFUNDED).forEach { status ->
            OrderStatusActor.entries.forEach { actor ->
                assertEquals(emptyList(), OrderStatusRules.nextStatuses(status, actor))
            }
        }
    }

    @Test
    fun `nextStatuses lists only what this actor may do next`() {
        assertEquals(listOf(CANCELLED), OrderStatusRules.nextStatuses(PENDING, CUSTOMER))
        assertEquals(listOf(CANCELLED), OrderStatusRules.nextStatuses(PENDING, ADMIN))
        assertEquals(listOf(CONFIRMED, CANCELLED), OrderStatusRules.nextStatuses(PENDING, SYSTEM))
        assertEquals(listOf(PROCESSING, CANCELLED), OrderStatusRules.nextStatuses(CONFIRMED, ADMIN))
        assertEquals(emptyList(), OrderStatusRules.nextStatuses(CONFIRMED, CUSTOMER))
        assertEquals(listOf(SHIPPED, CANCELLED), OrderStatusRules.nextStatuses(PROCESSING, ADMIN))
        assertEquals(listOf(DELIVERED), OrderStatusRules.nextStatuses(SHIPPED, ADMIN))
        assertEquals(listOf(REFUNDED), OrderStatusRules.nextStatuses(DELIVERED, ADMIN))
    }

    @Test
    fun `error messages name the change and the actor`() {
        val conflict = assertFailsWith<ConflictException> { OrderStatusRules.ensureCanChange(SHIPPED, PENDING, ADMIN) }
        assertEquals("Order status cannot change from SHIPPED to PENDING", conflict.message)

        val forbidden = assertFailsWith<ForbiddenException> {
            OrderStatusRules.ensureCanChange(CONFIRMED, CANCELLED, CUSTOMER)
        }
        assertEquals("CUSTOMER cannot change an order from CONFIRMED to CANCELLED", forbidden.message)
    }
}
