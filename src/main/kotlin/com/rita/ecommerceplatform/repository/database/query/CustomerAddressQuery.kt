package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object CustomerAddressQuery {

    const val FIND_BY_CUSTOMER = """
            SELECT * FROM ${PrefixConstant.ENTITY}CUSTOMER_ADDRESS
            WHERE customeraddresscustomerid = :customerId
            ORDER BY customeraddressisdefault DESC, customeraddresscreatedat DESC, customeraddressid DESC
            """

    const val FIND_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}CUSTOMER_ADDRESS WHERE customeraddressid = :addressId AND customeraddresscustomerid = :customerId"

    const val FIND_DEFAULT = "SELECT * FROM ${PrefixConstant.ENTITY}CUSTOMER_ADDRESS WHERE customeraddresscustomerid = :customerId AND customeraddressisdefault"

    const val COUNT_BY_CUSTOMER = "SELECT COUNT(*) FROM ${PrefixConstant.ENTITY}CUSTOMER_ADDRESS WHERE customeraddresscustomerid = :customerId"

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}CUSTOMER_ADDRESS (
                customeraddresscustomerid, customeraddressrecipientname, customeraddressrecipientphonenumber,
                customeraddressline1, customeraddressline2, customeraddresscity, customeraddressstate,
                customeraddresscountry, customeraddresspostalcode, customeraddressisdefault
            )
            VALUES (
                :customerId, :recipientName, :recipientPhoneNumber,
                :addressLine1, :addressLine2, :city, :state,
                :country, :postalCode, :isDefault
            )
            RETURNING customeraddressid
            """

    const val UPDATE = """
            UPDATE ${PrefixConstant.ENTITY}CUSTOMER_ADDRESS
            SET customeraddressrecipientname = :recipientName,
                customeraddressrecipientphonenumber = :recipientPhoneNumber,
                customeraddressline1 = :addressLine1,
                customeraddressline2 = :addressLine2,
                customeraddresscity = :city,
                customeraddressstate = :state,
                customeraddresscountry = :country,
                customeraddresspostalcode = :postalCode,
                customeraddressupdatedat = $SQL_NOW
            WHERE customeraddressid = :addressId AND customeraddresscustomerid = :customerId
            """

    const val CLEAR_DEFAULT = """
            UPDATE ${PrefixConstant.ENTITY}CUSTOMER_ADDRESS
            SET customeraddressisdefault = FALSE,
                customeraddressupdatedat = $SQL_NOW
            WHERE customeraddresscustomerid = :customerId AND customeraddressisdefault
            """

    const val MARK_DEFAULT = """
            UPDATE ${PrefixConstant.ENTITY}CUSTOMER_ADDRESS
            SET customeraddressisdefault = TRUE,
                customeraddressupdatedat = $SQL_NOW
            WHERE customeraddressid = :addressId AND customeraddresscustomerid = :customerId
            """

    const val DELETE = "DELETE FROM ${PrefixConstant.ENTITY}CUSTOMER_ADDRESS WHERE customeraddressid = :addressId AND customeraddresscustomerid = :customerId"
}
