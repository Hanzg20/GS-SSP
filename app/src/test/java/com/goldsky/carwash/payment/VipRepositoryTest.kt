package com.goldsky.carwash.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VipRepositoryTest {

    @Test
    fun `a successful RPC response maps to Success with the new balance`() {
        val result = classifyDeductResult(success = true, newBalanceCents = 2100, message = null)
        assertEquals(VipDeductResult.Success(2100), result)
    }

    @Test
    fun `success with a missing balance still maps to Success, defaulting to zero`() {
        // Shouldn't happen in practice (the RPC always returns new_balance_cents
        // on success), but decodeAs<T> can't guarantee it -- must not crash.
        val result = classifyDeductResult(success = true, newBalanceCents = null, message = null)
        assertEquals(VipDeductResult.Success(0), result)
    }

    @Test
    fun `insufficient_balance maps to Rejected with that reason`() {
        val result = classifyDeductResult(success = false, newBalanceCents = null, message = "insufficient_balance")
        assertEquals(VipDeductResult.Rejected("insufficient_balance"), result)
    }

    @Test
    fun `card_inactive and card_not_found are preserved as distinct reasons`() {
        assertEquals(
            VipDeductResult.Rejected("card_inactive"),
            classifyDeductResult(success = false, newBalanceCents = null, message = "card_inactive")
        )
        assertEquals(
            VipDeductResult.Rejected("card_not_found"),
            classifyDeductResult(success = false, newBalanceCents = null, message = "card_not_found")
        )
    }

    @Test
    fun `a rejection with no message falls back to an unknown reason instead of crashing`() {
        val result = classifyDeductResult(success = false, newBalanceCents = null, message = null)
        assertTrue(result is VipDeductResult.Rejected)
        assertEquals("unknown", (result as VipDeductResult.Rejected).reason)
    }
}
