package com.rita.ecommerceplatform.security

/**
 * Who a token belongs to. Stored in the JWT "userType" claim because customer
 * and admin IDs both start at 100: "sub": "100" alone would be ambiguous.
 */
enum class UserType { CUSTOMER, ADMIN }
