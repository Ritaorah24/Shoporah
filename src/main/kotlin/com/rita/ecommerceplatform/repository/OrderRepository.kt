package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.AddressDetails
import com.rita.ecommerceplatform.model.Order
import com.rita.ecommerceplatform.model.OrderPaymentStatus
import com.rita.ecommerceplatform.model.OrderStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
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
 * Customer queries are scoped by customerId, so a customer only ever sees their own orders.
 * Admin queries (findAllWithCustomer, findByIdWithCustomer, countAll, updateStatus) are not scoped.
 */
@Repository
class OrderRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        Order(
            orderId = rs.getLong("orderid"),
            reference = rs.getString("orderreference"),
            idempotencyKey = rs.getObject("orderidempotencykey", UUID::class.java),
            customerId = rs.getLong("ordercustomerid"),
            customerAddressId = rs.getObject("ordercustomeraddressid", Long::class.javaObjectType),
            deliveryMethodId = rs.getInt("orderdeliverymethodid"),
            couponId = rs.getObject("ordercouponid", Int::class.javaObjectType),
            deliveryAddress = AddressDetails(
                recipientName = rs.getString("orderrecipientname"),
                recipientPhoneNumber = rs.getString("orderrecipientphonenumber"),
                addressLine1 = rs.getString("orderaddressline1"),
                addressLine2 = rs.getString("orderaddressline2"),
                city = rs.getString("ordercity"),
                state = rs.getString("orderstate"),
                country = rs.getString("ordercountry"),
                postalCode = rs.getString("orderpostalcode"),
            ),
            subtotal = rs.getBigDecimal("ordersubtotal"),
            deliveryFee = rs.getBigDecimal("orderdeliveryfee"),
            discountAmount = rs.getBigDecimal("orderdiscountamount"),
            total = rs.getBigDecimal("ordertotal"),
            status = OrderStatus.valueOf(rs.getString("orderstatus").uppercase()),
            paymentStatus = OrderPaymentStatus.valueOf(rs.getString("orderpaymentstatus").uppercase()),
            paidAt = rs.getObject("orderpaidat", LocalDateTime::class.java),
            createdAt = rs.getObject("ordercreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("orderupdatedat", LocalDateTime::class.java),
        )
    }

    /**
     * Inserts an order and returns the generated orderid. Throws DuplicateKeyException if the
     * reference is taken (uq__ec_order__reference) or this customer already used the idempotency
     * key (uq__ec_order__customerid_idempotencykey): the service handles both.
     */
    fun insert(order: NewOrder): Long =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_order (
                orderreference, orderidempotencykey, ordercustomerid, ordercustomeraddressid, orderdeliverymethodid,
                orderrecipientname, orderrecipientphonenumber, orderaddressline1, orderaddressline2,
                ordercity, orderstate, ordercountry, orderpostalcode,
                ordersubtotal, orderdeliveryfee, orderdiscountamount, ordertotal
            )
            VALUES (
                :reference, :idempotencyKey, :customerId, :customerAddressId, :deliveryMethodId,
                :recipientName, :recipientPhoneNumber, :addressLine1, :addressLine2,
                :city, :state, :country, :postalCode,
                :subtotal, :deliveryFee, :discountAmount, :total
            )
            RETURNING orderid
            """
        )
            .param("reference", order.reference)
            .param("idempotencyKey", order.idempotencyKey)
            .param("customerId", order.customerId)
            .param("customerAddressId", order.customerAddressId)
            .param("deliveryMethodId", order.deliveryMethodId)
            .param("recipientName", order.deliveryAddress.recipientName)
            .param("recipientPhoneNumber", order.deliveryAddress.recipientPhoneNumber)
            .param("addressLine1", order.deliveryAddress.addressLine1)
            .param("addressLine2", order.deliveryAddress.addressLine2)
            .param("city", order.deliveryAddress.city)
            .param("state", order.deliveryAddress.state)
            .param("country", order.deliveryAddress.country)
            .param("postalCode", order.deliveryAddress.postalCode)
            .param("subtotal", order.subtotal)
            .param("deliveryFee", order.deliveryFee)
            .param("discountAmount", order.discountAmount)
            .param("total", order.total)
            .query(Long::class.javaObjectType)
            .single()

    /** True if any order already uses this reference (case-insensitive: CITEXT). */
    fun existsByReference(reference: String): Boolean =
        jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM public.ec_order WHERE orderreference = :reference)")
            .param("reference", reference)
            .query(Boolean::class.javaObjectType)
            .single()

    fun findById(orderId: Long, customerId: Long): Order? =
        jdbcClient.sql("SELECT * FROM public.ec_order WHERE orderid = :orderId AND ordercustomerid = :customerId")
            .param("orderId", orderId)
            .param("customerId", customerId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /**
     * The customer's order by reference, locked until the transaction ends, so two payment starts
     * for one order run one at a time. Must be called inside a transaction.
     */
    fun lockByReference(reference: String, customerId: Long): Order? =
        jdbcClient.sql(
            "SELECT * FROM public.ec_order WHERE orderreference = :reference AND ordercustomerid = :customerId FOR UPDATE"
        )
            .param("reference", reference)
            .param("customerId", customerId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** Locks the order row until the transaction ends (settle: order → payment → products). Must be called inside a transaction. */
    fun lockById(orderId: Long): Order? =
        jdbcClient.sql("SELECT * FROM public.ec_order WHERE orderid = :orderId FOR UPDATE")
            .param("orderId", orderId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /**
     * Marks a PENDING, UNPAID order PAID and moves it to `status` (CONFIRMED, or CANCELLED when stock ran out).
     * Returns rows updated: 0 means it was no longer PENDING + UNPAID, so it was never paid twice.
     */
    fun markPaid(orderId: Long, status: OrderStatus, paidAt: LocalDateTime): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_order
            SET orderstatus = :status, orderpaymentstatus = 'PAID', orderpaidat = :paidAt, orderupdatedat = $SQL_NOW
            WHERE orderid = :orderId AND orderstatus = 'PENDING' AND orderpaymentstatus = 'UNPAID'
            """
        )
            .param("orderId", orderId)
            .param("status", status.name)
            .param("paidAt", paidAt)
            .update()

    /**
     * Marks a PAID order REFUNDED and moves it from `from` to `to` (a cancelled order stays CANCELLED).
     * Returns rows updated: 0 means it was no longer `from` + PAID, so it is never refunded twice.
     */
    fun markRefunded(orderId: Long, from: OrderStatus, to: OrderStatus): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_order
            SET orderstatus = :to, orderpaymentstatus = 'REFUNDED', orderupdatedat = $SQL_NOW
            WHERE orderid = :orderId AND orderstatus = :from AND orderpaymentstatus = 'PAID'
            """
        )
            .param("orderId", orderId)
            .param("from", from.name)
            .param("to", to.name)
            .update()

    /** The order this customer already created with this idempotency key, if any (a retried checkout). */
    fun findByIdempotencyKey(customerId: Long, idempotencyKey: UUID): Order? =
        jdbcClient.sql(
            "SELECT * FROM public.ec_order WHERE ordercustomerid = :customerId AND orderidempotencykey = :idempotencyKey"
        )
            .param("customerId", customerId)
            .param("idempotencyKey", idempotencyKey)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** One page of the customer's orders, newest first (uses ix__ec_order__customerid_createdat). */
    fun findByCustomer(customerId: Long, limit: Int, offset: Long): List<Order> =
        jdbcClient.sql(
            """
            SELECT * FROM public.ec_order
            WHERE ordercustomerid = :customerId
            ORDER BY ordercreatedat DESC, orderid DESC
            LIMIT :limit OFFSET :offset
            """
        )
            .param("customerId", customerId)
            .param("limit", limit)
            .param("offset", offset)
            .query(rowMapper)
            .list()

    fun countByCustomer(customerId: Long): Long =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_order WHERE ordercustomerid = :customerId")
            .param("customerId", customerId)
            .query(Long::class.javaObjectType)
            .single()

    private val withCustomerMapper = RowMapper { rs, rowNum ->
        OrderWithCustomer(
            order = rowMapper.mapRow(rs, rowNum)!!,
            customerName = "${rs.getString("customerfirstname")} ${rs.getString("customerlastname")}",
            customerEmail = rs.getString("customeremail"),
        )
    }

    /** One page of every customer's orders, newest first; only those with `status` when it is given. */
    fun findAllWithCustomer(status: OrderStatus?, limit: Int, offset: Long): List<OrderWithCustomer> =
        jdbcClient.sql(
            """
            SELECT o.*, c.customerfirstname, c.customerlastname, c.customeremail
            FROM public.ec_order o
            JOIN public.ec_customer c ON c.customerid = o.ordercustomerid
            WHERE (CAST(:status AS text) IS NULL OR o.orderstatus = :status)
            ORDER BY o.ordercreatedat DESC, o.orderid DESC
            LIMIT :limit OFFSET :offset
            """
        )
            .param("status", status?.name)
            .param("limit", limit)
            .param("offset", offset)
            .query(withCustomerMapper)
            .list()

    fun countAll(status: OrderStatus?): Long =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_order WHERE (CAST(:status AS text) IS NULL OR orderstatus = :status)")
            .param("status", status?.name)
            .query(Long::class.javaObjectType)
            .single()

    fun findByIdWithCustomer(orderId: Long): OrderWithCustomer? =
        jdbcClient.sql(
            """
            SELECT o.*, c.customerfirstname, c.customerlastname, c.customeremail
            FROM public.ec_order o
            JOIN public.ec_customer c ON c.customerid = o.ordercustomerid
            WHERE o.orderid = :orderId
            """
        )
            .param("orderId", orderId)
            .query(withCustomerMapper)
            .optional()
            .orElse(null)

    /** Moves the order from `from` to `to`. Returns rows updated: 0 means it was no longer `from`. */
    fun updateStatus(orderId: Long, from: OrderStatus, to: OrderStatus): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_order
            SET orderstatus = :to, orderupdatedat = $SQL_NOW
            WHERE orderid = :orderId AND orderstatus = :from
            """
        )
            .param("orderId", orderId)
            .param("from", from.name)
            .param("to", to.name)
            .update()
}
