package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Test

class SalesTaxTest {

    @Test
    fun `zero-rated lines carry no tax`() {
        assertEquals(0, SalesTax.cents(listOf(1299 to 0, 249 to 0)))
    }

    @Test
    fun `HST rounds half up instead of truncating`() {
        // 299 * 13% = 38.87 -> 39 (truncation gave 38)
        assertEquals(39, SalesTax.cents(299, 1300))
        // 150 * 13% = 19.5 -> 20
        assertEquals(20, SalesTax.cents(150, 1300))
    }

    @Test
    fun `mixed cart taxes only the taxable lines, rounded once per rate`() {
        // rice 12.99 exempt + crackers 2.99 + herbal tea 1.49 at 13%:
        // (299 + 149) * 0.13 = 58.24 -> 58; per-line rounding would give 39 + 19 = 58 here,
        // but summing first is what keeps many small lines from drifting.
        assertEquals(58, SalesTax.cents(listOf(1299 to 0, 299 to 1300, 149 to 1300)))
    }

    @Test
    fun `rounding happens once per rate group, not per line`() {
        // Three 1.50 lines at 13%: per line 19.5 -> 20 each = 60; summed 4.50 * 13% = 58.5 -> 59
        assertEquals(59, SalesTax.cents(listOf(150 to 1300, 150 to 1300, 150 to 1300)))
    }

    @Test
    fun `byRate lists taxed rates only and sums to the total`() {
        val lines = listOf(1299 to 0, 299 to 1300, 100 to 1300)
        assertEquals(listOf(Triple(1300, 399, 52)), SalesTax.byRate(lines))
        assertEquals(SalesTax.cents(lines), SalesTax.byRate(lines).sumOf { it.third })
    }
}
