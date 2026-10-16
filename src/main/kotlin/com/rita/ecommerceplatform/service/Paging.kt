package com.rita.ecommerceplatform.service

/**
 * Page and size from query parameters, made safe (D6): the size is capped server-side so a client
 * cannot ask for the whole table in one request. Out-of-range values are corrected, not rejected.
 */
object Paging {
    const val DEFAULT_SIZE = 20
    const val MAX_SIZE = 50

    fun page(requested: Int): Int = requested.coerceAtLeast(0)

    fun size(requested: Int): Int = requested.coerceIn(1, MAX_SIZE)

    fun offset(page: Int, size: Int): Long = page.toLong() * size
}
