package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object OrderQuery {

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}ORDER (
                orderreference, orderidempotencykey, ordercustomerid, ordercustomeraddressid, orderdeliverymethodid, ordercouponid,
                orderrecipientname, orderrecipientphonenumber, orderaddressline1, orderaddressline2,
                ordercity, orderstate, ordercountry, orderpostalcode,
                ordersubtotal, orderdeliveryfee, orderdiscountamount, ordertotal
            )
            VALUES (
                :reference, :idempotencyKey, :customerId, :customerAddressId, :deliveryMethodId, :couponId,
                :recipientName, :recipientPhoneNumber, :addressLine1, :addressLine2,
                :city, :state, :country, :postalCode,
                :subtotal, :deliveryFee, :discountAmount, :total
            )
            RETURNING orderid
            """

    const val EXISTS_BY_REFERENCE = "SELECT EXISTS (SELECT 1 FROM ${PrefixConstant.ENTITY}ORDER WHERE orderreference = :reference)"

    const val FIND_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}ORDER WHERE orderid = :orderId AND ordercustomerid = :customerId"

    const val LOCK_BY_REFERENCE = "SELECT * FROM ${PrefixConstant.ENTITY}ORDER WHERE orderreference = :reference AND ordercustomerid = :customerId FOR UPDATE"

    const val LOCK_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}ORDER WHERE orderid = :orderId FOR UPDATE"

    const val MARK_PAID = """
            UPDATE ${PrefixConstant.ENTITY}ORDER
            SET orderstatus = :status, orderpaymentstatus = 'PAID', orderpaidat = :paidAt, orderupdatedat = $SQL_NOW
            WHERE orderid = :orderId AND orderstatus = 'PENDING' AND orderpaymentstatus = 'UNPAID'
            """

    const val MARK_REFUNDED = """
            UPDATE ${PrefixConstant.ENTITY}ORDER
            SET orderstatus = :to, orderpaymentstatus = 'REFUNDED', orderupdatedat = $SQL_NOW
            WHERE orderid = :orderId AND orderstatus = :from AND orderpaymentstatus = 'PAID'
            """

    const val FIND_BY_IDEMPOTENCY_KEY = "SELECT * FROM ${PrefixConstant.ENTITY}ORDER WHERE ordercustomerid = :customerId AND orderidempotencykey = :idempotencyKey"

    const val FIND_BY_CUSTOMER = """
            SELECT * FROM ${PrefixConstant.ENTITY}ORDER
            WHERE ordercustomerid = :customerId
            ORDER BY ordercreatedat DESC, orderid DESC
            LIMIT :limit OFFSET :offset
            """

    const val COUNT_BY_CUSTOMER = "SELECT COUNT(*) FROM ${PrefixConstant.ENTITY}ORDER WHERE ordercustomerid = :customerId"

    const val COUNT_BY_CUSTOMER_IDS = """
            SELECT ordercustomerid, COUNT(*) AS ordercount
            FROM ${PrefixConstant.ENTITY}ORDER
            WHERE ordercustomerid IN (<customerIds>)
            GROUP BY ordercustomerid
            """

    const val CUSTOMER_TOTALS = """
            SELECT COUNT(*) AS ordercount,
                   COUNT(*) FILTER (WHERE orderpaymentstatus = 'PAID') AS paidcount,
                   COALESCE(SUM(ordertotal) FILTER (WHERE orderpaymentstatus = 'PAID'), 0) AS spent
            FROM ${PrefixConstant.ENTITY}ORDER
            WHERE ordercustomerid = :customerId
            """

    const val FIND_BY_ID_WITH_CUSTOMER = """
            SELECT o.*, c.customerfirstname, c.customerlastname, c.customeremail
            FROM ${PrefixConstant.ENTITY}ORDER o
            JOIN ${PrefixConstant.ENTITY}CUSTOMER c ON c.customerid = o.ordercustomerid
            WHERE o.orderid = :orderId
            """

    const val UPDATE_STATUS = """
            UPDATE ${PrefixConstant.ENTITY}ORDER
            SET orderstatus = :to, orderupdatedat = $SQL_NOW
            WHERE orderid = :orderId AND orderstatus = :from
            """
}
