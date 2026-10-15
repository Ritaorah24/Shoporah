package com.rita.ecommerceplatform.security

/**
 * Extra authority given to admin tokens whose "mustChangePassword" claim is true (D4).
 * While present, the only admin endpoint allowed is PATCH /api/admin/auth/password.
 */
const val PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED"
