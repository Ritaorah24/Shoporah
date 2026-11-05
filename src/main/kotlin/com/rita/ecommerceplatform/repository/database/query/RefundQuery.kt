package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object RefundQuery {

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}REFUND (
                refundorderid, refundpaymentid, refundamount, refundcurrency, refundreason, refundrestock, refundrequestedbyadminid
            )
            VALUES (:orderId, :paymentId, :amount, :currency, :reason, :restock, :adminId)
            RETURNING refundid
            """

    const val FIND_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}REFUND WHERE refundid = :refundId"

    const val LOCK_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}REFUND WHERE refundid = :refundId FOR UPDATE"

    const val FIND_BY_ORDER = "SELECT * FROM ${PrefixConstant.ENTITY}REFUND WHERE refundorderid = :orderId ORDER BY refundcreatedat, refundid"

    const val FIND_BY_PROVIDER_REFUND_ID = "SELECT * FROM ${PrefixConstant.ENTITY}REFUND WHERE refundproviderrefundid = :providerRefundId"

    const val FIND_UNCONFIRMED_FOR_PAYMENT = """
            SELECT * FROM ${PrefixConstant.ENTITY}REFUND
            WHERE refundpaymentid = :paymentId AND refundstatus = 'PENDING' AND refundproviderrefundid IS NULL
            """

    const val FIND_PROVIDER_REFUND_IDS = "SELECT refundproviderrefundid FROM ${PrefixConstant.ENTITY}REFUND WHERE refundpaymentid = :paymentId AND refundproviderrefundid IS NOT NULL"

    const val SAVE_PROVIDER_DETAILS = """
            UPDATE ${PrefixConstant.ENTITY}REFUND
            SET refundproviderrefundid = :providerRefundId, refundproviderstatus = :providerStatus,
                refundproviderresponse = CAST(:rawResponse AS jsonb), refundupdatedat = $SQL_NOW
            WHERE refundid = :refundId AND refundstatus = 'PENDING'
            """

    const val MARK_PROCESSED = """
            UPDATE ${PrefixConstant.ENTITY}REFUND
            SET refundstatus = 'PROCESSED', refundproviderrefundid = :providerRefundId, refundproviderstatus = :providerStatus,
                refundproviderresponse = CAST(:rawResponse AS jsonb), refundprocessedat = $SQL_NOW, refundupdatedat = $SQL_NOW
            WHERE refundid = :refundId AND refundstatus = 'PENDING'
            """

    const val MARK_FAILED = """
            UPDATE ${PrefixConstant.ENTITY}REFUND
            SET refundstatus = 'FAILED', refundfailurereason = :reason,
                refundproviderrefundid = COALESCE(:providerRefundId, refundproviderrefundid),
                refundproviderstatus = COALESCE(:providerStatus, refundproviderstatus),
                refundproviderresponse = COALESCE(CAST(:rawResponse AS jsonb), refundproviderresponse),
                refundupdatedat = $SQL_NOW
            WHERE refundid = :refundId AND refundstatus = 'PENDING'
            """

    const val FAIL_UNCONFIRMED = """
            UPDATE ${PrefixConstant.ENTITY}REFUND
            SET refundstatus = 'FAILED', refundfailurereason = :reason, refundupdatedat = $SQL_NOW
            WHERE refundid = :refundId
              AND refundstatus = 'PENDING'
              AND refundproviderrefundid IS NULL
              AND refundcreatedat < $SQL_NOW - make_interval(secs => :olderThanSeconds)
            """
}
