package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant

object AdminQuery {

    const val SELECT_WITH_ROLE = """
        SELECT a.*, r.rolename
        FROM ${PrefixConstant.ENTITY}ADMIN a
        JOIN ${PrefixConstant.ENTITY}ROLE r ON r.roleid = a.adminroleid
    """

    const val FIND_BY_EMAIL = "$SELECT_WITH_ROLE WHERE a.adminemail = :email"

    const val FIND_BY_ID = "$SELECT_WITH_ROLE WHERE a.adminid = :adminId"
}
