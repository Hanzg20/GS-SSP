package com.goldsky.ssp.feature.timer

import com.goldsky.ssp.payment.hardware.IGpioProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurableOutputTest {

    /** Counts relay/logic "on" edges; triggerLogicPulse and holdRelayOutput both go through setRelay. */
    private class FakeGpio(private val accept: Boolean = true) : IGpioProvider {
        var onEdges = 0
        override fun setRelay(port: Int, on: Boolean): Boolean {
            if (on) onEdges++
            return accept
        }
        override fun readInput(port: Int) = 0
        override fun release() {}
    }

    @Test
    fun pulsesFollowPriceAndPulseValue() {
        assertEquals(2, OutputSettings(centsPerPulse = 100).pulsesFor(200))
        assertEquals(8, OutputSettings(centsPerPulse = 25).pulsesFor(200))
        assertEquals(1, OutputSettings(centsPerPulse = 200).pulsesFor(100)) // never zero pulses for a paid session
    }

    @Test
    fun coinModeSendsOnePulsePerUnitAndNothingOnResume() = runBlocking {
        val gpio = FakeGpio()
        val out = ConfigurableOutput(gpio) { OutputSettings(mode = OutputMode.COIN_PULSES, centsPerPulse = 100, pulseWidthMs = 1) }
        assertTrue(out.start(240_000, 300))
        assertEquals(3, gpio.onEdges)
        // A restart mid-session must not hand out the credit again.
        assertTrue(out.resume(120_000))
        assertEquals(3, gpio.onEdges)
    }

    @Test
    fun coinModeReportsRejectedPulse() = runBlocking {
        val out = ConfigurableOutput(FakeGpio(accept = false)) { OutputSettings(mode = OutputMode.COIN_PULSES, pulseWidthMs = 1) }
        assertEquals(false, out.start(240_000, 200))
    }
}
