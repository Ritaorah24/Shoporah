package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.OrderQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.containsPattern
import com.rita.ecommerceplatform.utility.startsWithPattern
import com.rita.ecommerceplatform.model.entity.AddressDetails
import com.rita.ecommerceplatform.model.entity.Order
import com.rita.ecommerceplatform.model.entity.OrderPaymentStatus
import com.rita.ecommerceplatform.model.entity.OrderStatus
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** Everything needed to insert an order. Status, payment status and timestamps come from column defaults. */
data class NewOrder(
    val reference: String,
    val idempotencyKey: UUID,
    val customerId: Long,
    val customerAddressId: Long?,
    val deliveryMethodId: Int,
    // The coupon whose use this order counted; null without one.
    val couponId: Int?,
    val deliveryAddress: AddressDetails,
    val subtotal: BigDecimal,
    val deliveryFee: BigDecimal,
    val discountAmount: BigDecimal,
    val total: BigDecimal,
)

/** An order with the customer who placed it, for the admin views. */
data class OrderWithCustomer(
    val order: Order,
    val customerName: String,
    val customerEmail: String,
)

/**
 * Filters for the admin order list; null means "any". The dates are in the platform's time (UTC+1):
 * placedFrom inclusive, placedBefore exclusive.
 */
data class AdminOrderSearch(
    val status: OrderStatus? = null,
    val paymentStatus: OrderPaymentStatus? = null,
    val referenceStartsWith: String? = null,
    val customerId: Long? = null,
    val customerContains: String? = null,
    val placedFrom: LocalDateTime? = null,
    val placedBefore: LocalDateTime? = null,
)

/** A customer's orders, as counted for the admin customer view. */
data class CustomerOrderTotals(val orderCount: Long, val paidOrderCount: Long, val totalSpent: BigDecimal)

/**
 * Customer queries are scoped by customerId, so a customer only ever sees their own orders.
 * Admin queries (findAllWithCustomer, findByIdWithCustomer, countAll, updateStatus) are not scoped.
 */
interface OrderRepository {
    /**
     * Inserts an order and returns the generated orderid. Throws DuplicateKeyException if the
     * reference is taken (uq__ec_order__reference) or this customer already used the idempotency
     * key (uq__ec_order__customerid_idempotencykey): the service handles both.
     */
    fun insert(order: NewOrder): Long

    /** True if any order already uses this reference (case-insensitive: CITEXT). */
    fun existsByReference(reference: String): Boolean

    fun findById(orderId: Long, customerId: Long): Order?

    /**
     * The customer's order by reference, locked until the transaction ends, so two payment starts
     * for one order run one at a time. Must be called inside a transaction.
     */
    fun lockByReference(reference: String, customerId: Long): Order?

    /** Locks the order row until the transaction ends (settle: order → payment → products). Must be called inside a transaction. */
    fun lockById(orderId: Long): Order?

    /**
     * Marks a PENDING, UNPAID order PAID and moves it to `status` (CONFIRMED, or CANCELLED when stock ran out).
     * Returns rows updated: 0 means it was no longer PENDING + UNPAID, so it was never paid twice.
     */
    fun markPaid(orderId: Long, status: OrderStatus, paidAt: LocalDateTime): Int

    /**
     * Marks a PAID order REFUNDED and moves it from `from` to `to` (a cancelled order stays CANCELLED).
     * Returns rows updated: 0 means it was no longer `from` + PAID, so it is never refunded twice.
     */
    fun markRefunded(orderId: Long, from: OrderStatus, to: OrderStatus): Int

    /** The order this customer already created with this idempotency key, if any (a retried checkout). */
    fun findByIdempotencyKey(customerId: Long, idempotencyKey: UUID): Order?

    /** One page of the customer's orders, newest first (uses ix__ec_order__customerid_createdat). */
    fun findByCustomer(customerId: Long, limit: Int, offset: Long): List<Order>

    fun countByCustomer(customerId: Long): Long

    /** One page of every customer's orders matching the search, newest first. */
    fun findAllWithCustomer(search: AdminOrderSearch, limit: Int, offset: Long): List<OrderWithCustomer>

    fun countAll(search: AdminOrderSearch): Long

    /** How many orders each of these customers has placed (one grouped query for a whole page of customers). */
    fun countByCustomerIds(customerIds: Collection<Long>): Map<Long, Long>

    /**
     * One customer's order totals for the admin view. Spent counts orders whose payment status is PAID: a refunded
     * order is REFUNDED, so it no longer counts; a cancelled order still waiting for its refund still does.
     */
    fun customerTotals(customerId: Long): CustomerOrderTotals

    fun findByIdWithCustomer(orderId: Long): OrderWithCustomer?

    /** Moves the order from `from` to `to`. Returns rows updated: 0 means it was no longer `from`. */
    fun updateStatus(orderId: Long, from: OrderStatus, to: OrderStatus): Int
}

@Repository
class OrderRepositoryImpl(private val jdbi: Jdbi) : OrderRepository {

    override fun insert(order: NewOrder): Long =
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(OrderQuery.INSERT)
                .bind("reference", order.reference)
                .bind("idempotencyKey", order.idempotencyKey)
                .bind("customerId", order.customerId)
                .bind("customerAddressId", order.customerAddressId)
                .bind("deliveryMethodId", order.deliveryMethodId)
                .bind("couponId", order.couponId)
                .bind("recipientName", order.deliveryAddress.recipientName)
                .bind("recipientPhoneNumber", order.deliveryAddress.recipientPhoneNumber)
                .bind("addressLine1", order.deliveryAddress.addressLine1)
                .bind("addressLine2", order.deliveryAddress.addressLine2)
                .bind("city", order.deliveryAddress.city)
                .bind("state", order.deliveryAddress.state)
                .bind("country", order.deliveryAddress.country)
                .bind("postalCode", order.deliveryAddress.postalCode)
                .bind("subtotal", order.subtotal)
                .bind("deliveryFee", order.deliveryFee)
                .bind("discountAmount", order.discountAmount)
                .bind("total", order.total)
                .mapTo<Long>()
                .one()
        }

    override fun existsByReference(reference: String): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(OrderQuery.EXISTS_BY_REFERENCE)
                .bind("reference", reference)
                .mapTo<Boolean>()
                .one()
        }

    override fun findById(orderId: Long, customerId: Long): Order? =
        jdbi.withHandle<Order?, Exception> { handle ->
            handle.createQuery(OrderQuery.FIND_BY_ID)
                .bind("orderId", orderId)
                .bind("customerId", customerId)
                .mapTo<Order>()
                .findOne()
                .orElse(null)
        }

    override fun lockByReference(reference: String, customerId: Long): Order? =
        jdbi.withHandle<Order?, Exception> { handle ->
            handle.createQuery(OrderQuery.LOCK_BY_REFERENCE)
                .bind("reference", reference)
                .bind("customerId", customerId)
                .mapTo<Order>()
                .findOne()
                .orElse(null)
        }

    override fun lockById(orderId: Long): Order? =
        jdbi.withHandle<Order?, Exception> { handle ->
            handle.createQuery(OrderQuery.LOCK_BY_ID)
                .bind("orderId", orderId)
                .mapTo<Order>()
                .findOne()
                .orElse(null)
        }

    override fun markPaid(orderId: Long, status: OrderStatus, paidAt: LocalDateTime): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(OrderQuery.MARK_PAID)
                .bind("orderId", orderId)
                .bind("status", status.name)
                .bind("paidAt", paidAt)
                .execute()
        }

    override fun markRefunded(orderId: Long, from: OrderStatus, to: OrderStatus): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(OrderQuery.MARK_REFUNDED)
                .bind("orderId", orderId)
                .bind("from", from.name)
                .bind("to", to.name)
                .execute()
        }

    override fun findByIdempotencyKey(customerId: Long, idempotencyKey: UUID): Order? =
        jdbi.withHandle<Order?, Exception> { handle ->
            handle.createQuery(OrderQuery.FIND_BY_IDEMPOTENCY_KEY)
                .bind("customerId", customerId)
                .bind("idempotencyKey", idempotencyKey)
                .mapTo<Order>()
                .findOne()
                .orElse(null)
        }

    override fun findByCustomer(customerId: Long, limit: Int, offset: Long): List<Order> =
        jdbi.withHandle<List<Order>, Exception> { handle ->
            handle.createQuery(OrderQuery.FIND_BY_CUSTOMER)
                .bind("customerId", customerId)
                .bind("limit", limit)
                .bind("offset", offset)
                .mapTo<Order>()
                .list()
        }

    override fun countByCustomer(customerId: Long): Long =
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(OrderQuery.COUNT_BY_CUSTOMER)
                .bind("customerId", customerId)
                .mapTo<Long>()
                .one()
        }

    private val withCustomerMapper = RowMapper { rs, ctx ->
        OrderWithCustomer(
            order = ctx.findRowMapperFor(Order::class.java).get().map(rs, ctx),
            customerName = "${rs.getString("customerfirstname")} ${rs.getString("customerlastname")}",
            customerEmail = rs.getString("customeremail"),
        )
    }

    override fun findAllWithCustomer(search: AdminOrderSearch, limit: Int, offset: Long): List<OrderWithCustomer> {
        val (where, params) = whereClause(search)
        return jdbi.withHandle<List<OrderWithCustomer>, Exception> { handle ->
            handle.createQuery("""
            SELECT o.*, c.customerfirstname, c.customerlastname, c.customeremail
            FROM ${PrefixConstant.ENTITY}ORDER o
            JOIN ${PrefixConstant.ENTITY}CUSTOMER c ON c.customerid = o.ordercustomerid
            $where
            ORDER BY o.ordercreatedat DESC, o.orderid DESC
            LIMIT :limit OFFSET :offset
            """)
                .bindMap(params + mapOf("limit" to limit, "offset" to offset))
                .map(withCustomerMapper)
                .list()
        }
    }

    override fun countAll(search: AdminOrderSearch): Long {
        val (where, params) = whereClause(search)
        return jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery("""
            SELECT COUNT(*)
            FROM ${PrefixConstant.ENTITY}ORDER o
            JOIN ${PrefixConstant.ENTITY}CUSTOMER c ON c.customerid = o.ordercustomerid
            $where
            """)
                .bindMap(params)
                .mapTo<Long>()
                .one()
        }
    }

    override fun countByCustomerIds(customerIds: Collection<Long>): Map<Long, Long> {
        if (customerIds.isEmpty()) return emptyMap()
        return jdbi.withHandle<Map<Long, Long>, Exception> { handle ->
            handle.createQuery(OrderQuery.COUNT_BY_CUSTOMER_IDS)
                .bindList("customerIds", customerIds)
            .map(RowMapper { rs, _ -> rs.getLong("ordercustomerid") to rs.getLong("ordercount") })
            .list()
            .toMap()
        }
    }

    override fun customerTotals(customerId: Long): CustomerOrderTotals =
        jdbi.withHandle<CustomerOrderTotals, Exception> { handle ->
            handle.createQuery(OrderQuery.CUSTOMER_TOTALS)
                .bind("customerId", customerId)
            .map(RowMapper { rs, _ -> CustomerOrderTotals(rs.getLong("ordercount"), rs.getLong("paidcount"), rs.getBigDecimal("spent")) })
            .one()
        }

    /**
     * Builds the WHERE clause from fixed SQL fragments only; every user value is a named parameter,
     * so search text can never change the SQL (like ProductRepository).
     */
    private fun whereClause(search: AdminOrderSearch): Pair<String, Map<String, Any>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        search.status?.let {
            conditions += "o.orderstatus = :status"
            params["status"] = it.name
        }
        search.paymentStatus?.let {
            conditions += "o.orderpaymentstatus = :paymentStatus"
            params["paymentStatus"] = it.name
        }
        search.referenceStartsWith?.let {
            conditions += "(o.orderreference::TEXT) ILIKE :referencePattern ESCAPE '!'"
            params["referencePattern"] = startsWithPattern(it)
        }
        search.customerId?.let {
            conditions += "o.ordercustomerid = :customerId"
            params["customerId"] = it
        }
        search.customerContains?.let {
            // Email, first name, last name or the full name ("Ada Obi").
            conditions += """
                ((c.customeremail::TEXT) ILIKE :customerPattern ESCAPE '!'
                 OR (c.customerfirstname::TEXT || ' ' || c.customerlastname::TEXT) ILIKE :customerPattern ESCAPE '!')
            """.trimIndent()
            params["customerPattern"] = containsPattern(it)
        }
        search.placedFrom?.let {
            conditions += "o.ordercreatedat >= :placedFrom"
            params["placedFrom"] = it
        }
        search.placedBefore?.let {
            conditions += "o.ordercreatedat < :placedBefore"
            params["placedBefore"] = it
        }
        return (if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")) to params
    }

    override fun findByIdWithCustomer(orderId: Long): OrderWithCustomer? =
        jdbi.withHandle<OrderWithCustomer?, Exception> { handle ->
            handle.createQuery(OrderQuery.FIND_BY_ID_WITH_CUSTOMER)
                .bind("orderId", orderId)
                .map(withCustomerMapper)
                .findOne()
                .orElse(null)
        }

    override fun updateStatus(orderId: Long, from: OrderStatus, to: OrderStatus): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(OrderQuery.UPDATE_STATUS)
                .bind("orderId", orderId)
                .bind("from", from.name)
                .bind("to", to.name)
                .execute()
        }
}
