package com.rita.ecommerceplatform.service

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Order reference format (checkpoint 1.3, Step 5, O3). Plain logic: no Spring, no database. */
class OrderReferenceGeneratorTest {

    private val generator = OrderReferenceGenerator()

    @Test
    fun `a reference is ORD, the date and 8 characters without look-alikes`() {
        val reference = generator.generate(LocalDate.of(2026, 10, 19))

        assertTrue(reference.matches(Regex("^ORD-20261019-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$")), reference)
        listOf('0', 'O', '1', 'I', 'L').forEach { assertTrue(it !in reference.substringAfterLast('-'), reference) }
    }

    @Test
    fun `references are random`() {
        val references = (1..1000).map { generator.generate(LocalDate.of(2026, 10, 19)) }.toSet()
        assertEquals(1000, references.size)
    }
}
