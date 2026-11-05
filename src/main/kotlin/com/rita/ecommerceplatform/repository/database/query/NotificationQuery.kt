package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object NotificationQuery {

    const val INSERT_PENDING = """
            INSERT INTO ${PrefixConstant.ENTITY}NOTIFICATION (
                notificationcustomerid, notificationadminid, notificationorderid, notificationchannel,
                notificationtype, notificationrecipient, notificationsubject, notificationbody
            )
            VALUES (:customerId, :adminId, :orderId, :channel, :type, :recipient, :subject, :body)
            ON CONFLICT DO NOTHING
            RETURNING notificationid
            """

    const val INSERT_LOG = """
            INSERT INTO ${PrefixConstant.ENTITY}NOTIFICATION (
                notificationcustomerid, notificationadminid, notificationchannel, notificationtype,
                notificationrecipient, notificationsubject, notificationbody, notificationstatus,
                notificationattemptcount, notificationsentat, notificationfailurereason
            )
            VALUES (
                :customerId, :adminId, :channel, :type, :recipient, :subject, :body, :status,
                1, CASE WHEN :status = 'SENT' THEN $SQL_NOW END, :reason
            )
            RETURNING notificationid
            """

    const val FIND_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}NOTIFICATION WHERE notificationid = :id"

    const val LOCK_NEXT_DUE = """
            SELECT * FROM ${PrefixConstant.ENTITY}NOTIFICATION
            WHERE notificationstatus = :pending
              AND (notificationattemptcount = 0
                   OR notificationupdatedat <= $SQL_NOW - (:retryDelaySeconds * INTERVAL '1 second'))
            ORDER BY notificationcreatedat, notificationid
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """

    const val MARK_SENT = """
            UPDATE ${PrefixConstant.ENTITY}NOTIFICATION
            SET notificationstatus = :sent,
                notificationattemptcount = notificationattemptcount + 1,
                notificationsentat = $SQL_NOW,
                notificationfailurereason = NULL,
                notificationupdatedat = $SQL_NOW
            WHERE notificationid = :id
            """

    const val MARK_ATTEMPT_FAILED = """
            UPDATE ${PrefixConstant.ENTITY}NOTIFICATION
            SET notificationattemptcount = notificationattemptcount + 1,
                notificationstatus = CASE WHEN notificationattemptcount + 1 >= :maxAttempts THEN :failed ELSE :pending END,
                notificationfailurereason = :reason,
                notificationupdatedat = $SQL_NOW
            WHERE notificationid = :id
            """
}
