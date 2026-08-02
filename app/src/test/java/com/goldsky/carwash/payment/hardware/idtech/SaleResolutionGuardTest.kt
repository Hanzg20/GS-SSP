package com.goldsky.carwash.payment.hardware.idtech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IdTechPaymentProvider.startSale() arms EMV+CTLS+MSR concurrently; this
 * guard is what stops two of those three modes from both resolving the same
 * sale (double onSuccess/onFailure -> double startFinalizationSequence()).
 */
class SaleResolutionGuardTest {

    @Test
    fun `first resolve runs the action and reports it ran`() {
        val guard = SaleResolutionGuard()
        var calls = 0

        val ran = guard.resolve { calls++ }

        assertTrue(ran)
        assertEquals(1, calls)
    }

    @Test
    fun `a second resolve after the first is ignored`() {
        val guard = SaleResolutionGuard()
        var calls = 0

        guard.resolve { calls++ }
        val secondRan = guard.resolve { calls++ }

        assertFalse(secondRan)
        assertEquals(1, calls)
    }

    @Test
    fun `many resolves after the first all no-op`() {
        val guard = SaleResolutionGuard()
        var calls = 0

        guard.resolve { calls++ }
        repeat(10) { guard.resolve { calls++ } }

        assertEquals(1, calls)
    }

    @Test
    fun `reset allows resolving again for a new attempt`() {
        val guard = SaleResolutionGuard()
        var calls = 0

        guard.resolve { calls++ }
        guard.reset()
        val ranAfterReset = guard.resolve { calls++ }

        assertTrue(ranAfterReset)
        assertEquals(2, calls)
    }

    @Test
    fun `a fresh guard has not resolved yet`() {
        val guard = SaleResolutionGuard()
        var calls = 0

        // Sanity check the initial state doesn't already look "resolved".
        val ran = guard.resolve { calls++ }

        assertTrue(ran)
        assertEquals(1, calls)
    }
}
