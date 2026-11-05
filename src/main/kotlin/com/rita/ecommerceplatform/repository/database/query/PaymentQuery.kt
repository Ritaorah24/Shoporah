package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object PaymentQuery {

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}PAYMENT (paymentorderid, paymentreference, paymentprovider, paymentamount, paymentcurrency)
            VALUES (:orderId, :reference, :provider, :amount, :currency)
            RETURNING paymentid
            """

    const val FIND_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}PAYMENT WHERE paymentid = :paymentId"

    const val FIND_BY_REFERENCE_FOR_CUSTOMER = """
            SELECT p.* FROM ${PrefixConstant.ENTITY}PAYMENT p
            JOIN ${PrefixConstant.ENTITY}ORDER o ON o.orderid = p.paymentorderid
            WHERE p.paymentreference = :reference AND o.ordercustomerid = :customerId
            """

    const val FIND_BY_REFERENCE = "SELECT * FROM ${PrefixConstant.ENTITY}PAYMENT WHERE paymentreference = :reference"

    const val LOCK_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}PAYMENT WHERE paymentid = :paymentId FOR UPDATE"

    const val FIND_SUCCESSFUL_FOR_ORDER = "SELECT * FROM ${PrefixConstant.ENTITY}PAYMENT WHERE paymentorderid = :orderId AND paymentstatus = 'SUCCESS'"

    const val FIND_BY_ORDER = "SELECT * FROM ${PrefixConstant.ENTITY}PAYMENT WHERE paymentorderid = :orderId ORDER BY paymentcreatedat, paymentid"

    const val FIND_OPEN_ATTEMPT = """
            SELECT * FROM ${PrefixConstant.ENTITY}PAYMENT
            WHERE paymentorderid = :orderId AND paymentstatus = 'PENDING'
            ORDER BY paymentcreatedat DESC, paymentid DESC
            LIMIT 1
            """

    const val FAIL_STALE_STARTS = """
            UPDATE ${PrefixConstant.ENTITY}PAYMENT
            SET paymentstatus = 'FAILED', paymentgatewayresponse = :reason, paymentupdatedat = $SQL_NOW
            WHERE paymentorderid = :orderId
              AND paymentstatus = 'PENDING'
              AND paymentauthorizationurl IS NULL
              AND paymentcreatedat < $SQL_NOW - make_interval(secs => :olderThanSeconds)
            """

    const val SAVE_AUTHORIZATION_URL = """
            UPDATE ${PrefixConstant.ENTITY}PAYMENT
            SET paymentauthorizationurl = :authorizationUrl, paymentupdatedat = $SQL_NOW
            WHERE paymentid = :paymentId
            """

    const val MARK_SUCCEEDED = """
            UPDATE ${PrefixConstant.ENTITY}PAYMENT
            SET paymentstatus = 'SUCCESS', paymentprovidertransactionid = :transactionId, paymentchannel = :channel,
                paymentgatewayresponse = :gatewayResponse, paymentproviderresponse = CAST(:rawResponse AS jsonb),
                paymentpaidat = :paidAt, paymentverifiedat = $SQL_NOW, paymentupdatedat = $SQL_NOW
            WHERE paymentid = :paymentId AND paymentstatus = 'PENDING'
            """

    const val MARK_UNSUCCESSFUL = """
            UPDATE ${PrefixConstant.ENTITY}PAYMENT
            SET paymentstatus = :status, paymentprovidertransactionid = :transactionId, paymentchannel = :channel,
                paymentgatewayresponse = :gatewayResponse, paymentproviderresponse = CAST(:rawResponse AS jsonb),
                paymentverifiedat = $SQL_NOW, paymentupdatedat = $SQL_NOW
            WHERE paymentid = :paymentId AND paymentstatus = 'PENDING'
            """

    const val MARK_FAILED = """
            UPDATE ${PrefixConstant.ENTITY}PAYMENT
            SET paymentstatus = 'FAILED', paymentgatewayresponse = :reason, paymentupdatedat = $SQL_NOW
            WHERE paymentid = :paymentId AND paymentstatus = 'PENDING'
            """
}
