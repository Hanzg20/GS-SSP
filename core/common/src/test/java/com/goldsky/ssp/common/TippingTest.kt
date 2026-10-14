package com.goldsky.ssp.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TippingTest {

    @Test
    fun `percentages are of the amount before tax, rounded to the nearest cent`() {
        // $42.37 before tax: 15% = 6.3555, 18% = 7.6266, 20% = 8.474
        assertEquals(
            listOf(Tipping.Option(636, 15), Tipping.Option(763, 18), Tipping.Option(847, 20)),
            Tipping.options(4237)
        )
    }

    @Test
    fun `half a cent rounds up`() {
        assertEquals(15, Tipping.percentOf(100, 1450)) // 14.5% of $1.00 = 14.5 cents
        assertEquals(2, Tipping.percentOf(10, 1500))   // 1.5 cents
    }

    @Test
    fun `under ten dollars the options are one two and three dollars`() {
        assertEquals(listOf(Tipping.Option(100, null), Tipping.Option(200, null), Tipping.Option(300, null)), Tipping.options(999))
        assertEquals(15, Tipping.options(1000).first().percent)
    }

    @Test
    fun `a typed-in tip may be up to the order or twenty dollars, whichever is more`() {
        assertEquals(2000, Tipping.maxCustomCents(1200))
        assertEquals(15_000, Tipping.maxCustomCents(15_000))
    }
}
