package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object CustomerAuthQuery {

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}CUSTOMER_AUTH (customerauthcustomerid, customerauthpasswordhash)
            VALUES (:customerId, :passwordHash)
            RETURNING customerauthid
            """

    const val FIND_BY_CUSTOMER_ID = "SELECT * FROM ${PrefixConstant.ENTITY}CUSTOMER_AUTH WHERE customerauthcustomerid = :customerId"

    const val UPDATE_LAST_LOGIN_AT = """
            UPDATE ${PrefixConstant.ENTITY}CUSTOMER_AUTH
            SET customerauthlastloginat = $SQL_NOW,
                customerauthupdatedat = $SQL_NOW
            WHERE customerauthcustomerid = :customerId
            """

    const val UPDATE_PASSWORD = """
            UPDATE ${PrefixConstant.ENTITY}CUSTOMER_AUTH
            SET customerauthpasswordhash = :passwordHash,
                customerauthpasswordchangedat = $SQL_NOW,
                customerauthupdatedat = $SQL_NOW
            WHERE customerauthcustomerid = :customerId
            """

    const val SAVE_RESET_TOKEN = """
            UPDATE ${PrefixConstant.ENTITY}CUSTOMER_AUTH
            SET customerauthresettokenhash = :tokenHash,
                customerauthresettokenexpiresat = $SQL_NOW + (:validMinutes * INTERVAL '1 minute'),
                customerauthupdatedat = $SQL_NOW
            WHERE customerauthcustomerid = :customerId
              AND (customerauthresettokenexpiresat IS NULL
                   OR customerauthresettokenexpiresat
                      <= $SQL_NOW + (:validMinutes * INTERVAL '1 minute') - (:cooldownSeconds * INTERVAL '1 second'))
            """

    const val FIND_BY_VALID_RESET_TOKEN_HASH = """
            SELECT * FROM ${PrefixConstant.ENTITY}CUSTOMER_AUTH
            WHERE customerauthresettokenhash = :tokenHash
              AND customerauthresettokenexpiresat > $SQL_NOW
            """

    const val RESET_PASSWORD = """
            UPDATE ${PrefixConstant.ENTITY}CUSTOMER_AUTH
            SET customerauthpasswordhash = :passwordHash,
                customerauthpasswordchangedat = $SQL_NOW,
                customerauthresettokenhash = NULL,
                customerauthresettokenexpiresat = NULL,
                customerauthfailedloginattempts = 0,
                customerauthlockeduntil = NULL,
                customerauthupdatedat = $SQL_NOW
            WHERE customerauthresettokenhash = :tokenHash
              AND customerauthresettokenexpiresat > $SQL_NOW
            """
}
