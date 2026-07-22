package com.goldsky.carwash.payment

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeyHealthMonitorTest {

    @Before
    @After
    fun reset() {
        // KeyHealthMonitor is a process-wide singleton; state must not leak between tests.
        KeyHealthMonitor.reset()
    }

    @Test
    fun `starts unlocked`() {
        assertTrue(KeyHealthMonitor.isPaymentAllowed())
        assertEquals(null, KeyHealthMonitor.lockReason())
    }

    @Test
    fun `non-key failures never lock the terminal`() {
        repeat(10) {
            KeyHealthMonitor.recordResult("000010", "Card declined")
        }
        assertTrue(KeyHealthMonitor.isPaymentAllowed())
    }

    @Test
    fun `a single key failure does not lock yet`() {
        KeyHealthMonitor.recordResult("999901", "DUKPT key missing")
        assertTrue(KeyHealthMonitor.isPaymentAllowed())
    }

    @Test
    fun `two consecutive key failures lock the terminal`() {
        KeyHealthMonitor.recordResult("999901", "DUKPT key missing")
        KeyHealthMonitor.recordResult("999901", "DUKPT key missing")
        assertFalse(KeyHealthMonitor.isPaymentAllowed())
        assertEquals("DUKPT key missing", KeyHealthMonitor.lockReason())
    }

    @Test
    fun `a successful result in between resets the failure streak`() {
        KeyHealthMonitor.recordResult("999901", "PIN PAD not ready")
        KeyHealthMonitor.recordResult("000000", "Approved")
        KeyHealthMonitor.recordResult("999901", "PIN PAD not ready")
        assertTrue(KeyHealthMonitor.isPaymentAllowed())
    }

    @Test
    fun `reset clears the lock`() {
        KeyHealthMonitor.recordResult("999901", "KEY ERROR")
        KeyHealthMonitor.recordResult("999901", "KEY ERROR")
        assertFalse(KeyHealthMonitor.isPaymentAllowed())

        KeyHealthMonitor.reset()

        assertTrue(KeyHealthMonitor.isPaymentAllowed())
        assertEquals(null, KeyHealthMonitor.lockReason())
    }
}
