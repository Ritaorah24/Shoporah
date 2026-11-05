package com.rita.ecommerceplatform.repository.database.query

import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.SQL_NOW

object CouponQuery {

    const val FIND_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}COUPON WHERE couponid = :id"

    const val LOCK_BY_ID = "SELECT * FROM ${PrefixConstant.ENTITY}COUPON WHERE couponid = :id FOR UPDATE"

    const val FIND_BY_CODE = "SELECT * FROM ${PrefixConstant.ENTITY}COUPON WHERE couponcode = :code"

    const val LOCK_BY_CODE = "SELECT * FROM ${PrefixConstant.ENTITY}COUPON WHERE couponcode = :code FOR UPDATE"

    const val REDEEM = """
            UPDATE ${PrefixConstant.ENTITY}COUPON
            SET couponusagecount = couponusagecount + 1,
                couponupdatedat = $SQL_NOW
            WHERE couponid = :id
              AND couponstatus = 'ACTIVE'
              AND (couponusagelimit IS NULL OR couponusagecount < couponusagelimit)
            """

    const val RELEASE = """
            UPDATE ${PrefixConstant.ENTITY}COUPON
            SET couponusagecount = couponusagecount - 1,
                couponupdatedat = $SQL_NOW
            WHERE couponid = :id AND couponusagecount > 0
            """

    const val EXISTS_BY_CODE = "SELECT EXISTS (SELECT 1 FROM ${PrefixConstant.ENTITY}COUPON WHERE couponcode = :code)"

    const val INSERT = """
            INSERT INTO ${PrefixConstant.ENTITY}COUPON (
                couponcode, coupondescription, coupondiscounttype, coupondiscountvalue, couponmaxdiscountamount,
                couponminimumordervalue, couponusagelimit, couponstartsat, couponexpiresat, couponcreatedbyadminid
            )
            VALUES (
                :code, :description, :discountType, :discountValue, :maxDiscountAmount,
                :minimumOrderValue, :usageLimit, :startsAt, :expiresAt, :adminId
            )
            RETURNING couponid
            """

    const val UPDATE = """
            UPDATE ${PrefixConstant.ENTITY}COUPON
            SET coupondescription = :description,
                coupondiscounttype = :discountType,
                coupondiscountvalue = :discountValue,
                couponmaxdiscountamount = :maxDiscountAmount,
                couponminimumordervalue = :minimumOrderValue,
                couponusagelimit = :usageLimit,
                couponstartsat = :startsAt,
                couponexpiresat = :expiresAt,
                couponupdatedat = $SQL_NOW
            WHERE couponid = :id AND couponstatus <> 'DELETED'
            """

    const val UPDATE_STATUS = """
            UPDATE ${PrefixConstant.ENTITY}COUPON
            SET couponstatus = :status,
                couponupdatedat = $SQL_NOW
            WHERE couponid = :id AND couponstatus <> 'DELETED'
            """
}
