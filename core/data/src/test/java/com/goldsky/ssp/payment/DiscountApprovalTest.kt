package com.goldsky.ssp.payment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typed-in discounts: staff up to 10% and up to $10; beyond either, a manager. */
class DiscountApprovalTest {

    private fun needs(d: Discount, base: Int) = DiscountPresetRepository.needsManager(d, base)

    @Test
    fun `ten percent is within the staff limit, a cent more is not`() {
        assertFalse(needs(Discount("x", percentBps = 1000), 1295))
        assertTrue(needs(Discount("x", percentBps = 1001), 100_000))
        assertFalse(needs(Discount("x", amountCents = 129), 1295))
        assertTrue(needs(Discount("x", amountCents = 131), 1295))
    }

    @Test
    fun `ten dollars is within the staff limit even on a big order, more is not`() {
        assertFalse(needs(Discount("x", amountCents = 1000), 20_000))
        assertTrue(needs(Discount("x", amountCents = 1001), 20_000))
        assertTrue(needs(Discount("x", percentBps = 600), 20_000)) // 6% of $200 = $12
    }

    @Test
    fun `an amount bigger than the line only counts what it can take off`() {
        assertTrue(needs(Discount("x", amountCents = 5000), 250)) // the whole $2.50 line: 100%
    }
}
