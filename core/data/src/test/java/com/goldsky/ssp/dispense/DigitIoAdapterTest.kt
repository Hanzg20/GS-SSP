package com.goldsky.ssp.dispense

import android.content.Context
import com.goldsky.ssp.dispense.ack.AssumedSuccessAckStrategy
import com.goldsky.ssp.dispense.adapter.DigitIoAdapter
import com.goldsky.ssp.payment.hardware.IGpioProvider
import com.goldsky.ssp.payment.hardware.ISerialProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitIoAdapterTest {

    /** Accepts the first [acceptFirst] pulses, then refuses (like -87 "Too many users"). */
    private class FakeGpio(private val acceptFirst: Int) : IGpioProvider {
        var calls = 0
        override fun setRelay(port: Int, on: Boolean) = true
        override fun readInput(port: Int) = 0
        override fun release() {}
        override suspend fun triggerLogicPulse(port: Int, voltage: Int, onMs: Long, offMs: Long): Boolean = ++calls <= acceptFirst
    }

    private object NoSerial : ISerialProvider {
        override fun open(context: Context) = true
        override fun close() {}
        override fun isOpened() = true
        override fun sendBytes(data: ByteArray) = true
        override fun sendHexString(hexStr: String) = true
        override suspend fun sendCommandWithAck(hexStr: String, timeoutMs: Int, maxRetries: Int) = true
    }

    // No org config loaded in a unit test -> default 25c per pulse: $1 = 4 pulses.
    private fun run(acceptFirst: Int) = runBlocking {
        DigitIoAdapter().dispense(DispenseJob(100, "", "SN", "REF"), AssumedSuccessAckStrategy(), NoSerial, FakeGpio(acceptFirst)) { _, _ -> }
    }

    @Test
    fun allPulsesRejected_failsEvenInAssumedSuccessMode_soThePaymentIsVoided() {
        assertTrue(run(acceptFirst = 0) is DispenseOutcome.Failed)
    }

    @Test
    fun somePulsesRejected_isDeliveredButFlaggedPartial() {
        val outcome = run(acceptFirst = 2)
        assertTrue(outcome is DispenseOutcome.DeliveredUnconfirmed)
        assertEquals("${DigitIoAdapter.PARTIAL_PREFIX}2/4 pulses accepted by the hardware", (outcome as DispenseOutcome.DeliveredUnconfirmed).detail)
    }

    @Test
    fun allPulsesAccepted_isConfirmed() {
        assertTrue(run(acceptFirst = 4) is DispenseOutcome.Confirmed)
    }
}
