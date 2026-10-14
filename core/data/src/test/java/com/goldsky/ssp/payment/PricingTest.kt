package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PricingTest {

    private val hst = 1300

    @Test
    fun `no discounts is plain subtotal and per-rate tax`() {
        val r = Pricing.price(listOf(Pricing.Line(1645, hst), Pricing.Line(200, 0)))
        assertEquals(1845, r.netCents)
        assertEquals(0, r.discountCents)
        assertEquals(214, r.taxCents) // 1645 * 13% = 213.85
    }

    @Test
    fun `the design's worked example`() {
        // Beef 16.45, Zhajiang 12.95 at Senior 10%, Coke 2 x 2.50, Water 2.00 (exempt), Loyalty $3 off the order.
        val r = Pricing.price(
            listOf(
                Pricing.Line(1645, hst),
                Pricing.Line(1295, hst, Discount("Senior", percentBps = 1000)),
                Pricing.Line(500, hst),
                Pricing.Line(200, 0),
            ),
            orderDiscount = Discount("Loyalty", amountCents = 300),
        )
        assertEquals(3640, r.grossCents)
        assertEquals(130, r.lineDiscountCents)
        assertEquals(300, r.orderDiscountCents)
        assertEquals(430, r.discountCents)
        assertEquals(3210, r.netCents)
        assertEquals(listOf(141, 99, 43, 17), r.lines.map { it.orderShareCents })
        assertEquals(394, r.taxCents)   // 13% of 3027
        assertEquals(3604, r.totalCents)
    }

    @Test
    fun `order discount shares always add up to the discount`() {
        val weights = listOf(333, 333, 334)
        assertEquals(100, Pricing.allocate(100, weights).sum())
        assertEquals(listOf(33, 33, 34), Pricing.allocate(100, weights))
        assertEquals(listOf(0, 0), Pricing.allocate(0, listOf(5, 5)))
        assertEquals(listOf(0, 0), Pricing.allocate(10, listOf(0, 0)))
    }

    @Test
    fun `an amount larger than the line takes it to zero, never below`() {
        val r = Pricing.price(listOf(Pricing.Line(250, hst, Discount("Comp", amountCents = 999))))
        assertEquals(0, r.netCents)
        assertEquals(0, r.taxCents)
    }

    @Test
    fun `a 100 percent order discount leaves nothing to tax`() {
        val r = Pricing.price(listOf(Pricing.Line(1000, hst), Pricing.Line(500, 0)), Discount("Comp", percentBps = 10_000))
        assertEquals(0, r.totalCents)
    }

    @Test
    fun `percentages round half up`() {
        assertEquals(130, Discount("x", percentBps = 1000).offCents(1295)) // 129.5
        assertEquals(129, Discount("x", percentBps = 1000).offCents(1294)) // 129.4
    }

    @Test
    fun `a discount is a percentage or an amount, not both or neither`() {
        assertThrows(IllegalArgumentException::class.java) { Discount("x") }
        assertThrows(IllegalArgumentException::class.java) { Discount("x", percentBps = 100, amountCents = 100) }
        assertThrows(IllegalArgumentException::class.java) { Discount("x", percentBps = 10_001) }
    }
}
