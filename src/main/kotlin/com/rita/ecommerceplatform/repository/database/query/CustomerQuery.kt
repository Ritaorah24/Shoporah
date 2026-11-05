package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object CustomerQuery {

    const val EXISTS_BY_EMAIL = "SELECT EXISTS (SELECT 1 FROM ${PrefixConstant.ENTITY}CUSTOMER WHERE customeremail = :email)"

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}CUSTOMER (customerfirstname, customerlastname, customeremail, customerphonenumber)
            VALUES (:firstName, :lastName, :email, :phoneNumber)
            RETURNING customerid
            """

    const val FIND_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}CUSTOMER WHERE customerid = :customerId"

    const val LOCK_BY_ID = "SELECT customerid FROM ${PrefixConstant.ENTITY}CUSTOMER WHERE customerid = :customerId FOR UPDATE"

    const val FIND_BY_EMAIL = "SELECT * FROM ${PrefixConstant.ENTITY}CUSTOMER WHERE customeremail = :email"

    const val UPDATE_PROFILE = """
            UPDATE ${PrefixConstant.ENTITY}CUSTOMER
            SET customerfirstname = :firstName,
                customerlastname = :lastName,
                customerphonenumber = :phoneNumber,
                customerupdatedat = $SQL_NOW
            WHERE customerid = :customerId
            """
}
