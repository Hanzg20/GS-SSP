package com.goldsky.ssp.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TippingTest {

    @Test
    fun `percentages are of the amount before tax, rounded to the nearest cent`() {
        // $42.37 before tax: 10% = 4.237, 12% = 5.0844, 15% = 6.3555
        assertEquals(
            listOf(Tipping.Option(424, 10), Tipping.Option(508, 12), Tipping.Option(636, 15)),
            Tipping.options(4237)
        )
    }

    @Test
    fun `half a cent rounds up`() {
        assertEquals(15, Tipping.percentOf(100, 1450)) // 14.5% of $1.00 = 14.5 cents
        assertEquals(2, Tipping.percentOf(10, 1500))   // 1.5 cents
    }

    @Test
    fun `small orders get the same percentages`() {
        // $2.50 before tax: 25, 30, 37.5 -> 38 cents
        assertEquals(listOf(Tipping.Option(25, 10), Tipping.Option(30, 12), Tipping.Option(38, 15)), Tipping.options(250))
    }

    @Test
    fun `a typed-in tip may be up to the order or twenty dollars, whichever is more`() {
        assertEquals(2000, Tipping.maxCustomCents(1200))
        assertEquals(15_000, Tipping.maxCustomCents(15_000))
    }
}
