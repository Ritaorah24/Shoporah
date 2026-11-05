package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object AdminAuthQuery {

    const val FIND_BY_ADMIN_ID = "SELECT * FROM ${PrefixConstant.ENTITY}ADMIN_AUTH WHERE adminauthadminid = :adminId"

    const val UPDATE_LAST_LOGIN_AT = """
            UPDATE ${PrefixConstant.ENTITY}ADMIN_AUTH
            SET adminauthlastloginat = $SQL_NOW,
                adminauthupdatedat = $SQL_NOW
            WHERE adminauthadminid = :adminId
            """

    const val UPDATE_PASSWORD = """
            UPDATE ${PrefixConstant.ENTITY}ADMIN_AUTH
            SET adminauthpasswordhash = :passwordHash,
                adminauthpasswordstatus = :passwordStatus,
                adminauthpasswordchangedat = $SQL_NOW,
                adminauthupdatedat = $SQL_NOW
            WHERE adminauthadminid = :adminId
            """
}
