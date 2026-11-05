package com.rita.ecommerceplatform.utility

/**
 * ILIKE patterns for search text, used with ESCAPE '!'. A search for "50%" or "a_b" matches those characters
 * literally instead of as wildcards. The text is always passed as a named parameter, never put into the SQL.
 * The escape is not a backslash because JDBI's SQL parser cannot read ESCAPE with a backslash.
 */
internal fun containsPattern(term: String): String = "%${escapeLike(term)}%"

internal fun startsWithPattern(term: String): String = "${escapeLike(term)}%"

private fun escapeLike(term: String): String =
    term.replace("!", "!!").replace("%", "!%").replace("_", "!_")
