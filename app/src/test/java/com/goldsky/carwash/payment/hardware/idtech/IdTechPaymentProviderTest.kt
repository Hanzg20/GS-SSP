package com.goldsky.carwash.payment.hardware.idtech

import com.goldsky.carwash.payment.hardware.IPaymentProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers what's reachable through the public [IPaymentProvider] API without a
 * real [com.idtechproducts.device.IDT_NEO2] reader attached (which needs a
 * live Android Context this plain-JUnit suite doesn't have) -- specifically
 * the "reader not initialized" fail-safe paths and the no-op passthrough
 * behavior of startCardDetection(). Behavior that depends on a real reader
 * (emv_startTransaction etc. actually being invoked, SDK callback routing)
 * is covered separately by EmvResultClassifierTest and SaleResolutionGuardTest,
 * which test the pure logic those paths delegate to.
 */
class IdTechPaymentProviderTest {

    /** Records every callback invocation instead of asserting inline, so each test can pick what matters. */
    private class RecordingCallback : IPaymentProvider.PaymentCallback {
        var successCalls = 0
        var entryModes = mutableListOf<String>()
        var failureMessages = mutableListOf<String>()
        var hardwareFaultFlags = mutableListOf<Boolean>()
        var progressMessages = mutableListOf<String>()

        override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
            successCalls++
            entryModes.add(entryMode)
        }

        override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
            failureMessages.add(errorMsg)
            hardwareFaultFlags.add(isHardwareFault)
        }

        override fun onProgress(message: String) {
            progressMessages.add(message)
        }
    }

    private lateinit var provider: IdTechPaymentProvider
    private lateinit var callback: RecordingCallback

    @Before
    fun setUp() {
        provider = IdTechPaymentProvider()
        callback = RecordingCallback()
    }

    @Test
    fun `startCardDetection always succeeds immediately without touching hardware`() {
        // No attachReader() call at all -- if this touched the reader it would NPE.
        provider.startCardDetection(1000, callback)

        assertEquals(1, callback.successCalls)
        assertEquals(listOf("UNKNOWN"), callback.entryModes)
        assertEquals(0, callback.failureMessages.size)
    }

    @Test
    fun `startSale without an attached reader fails as a hardware fault, not a business decline`() {
        provider.startSale(1000, "ECR_1", callback)

        assertEquals(0, callback.successCalls)
        assertEquals(listOf("Reader not initialized"), callback.failureMessages)
        assertEquals(listOf(true), callback.hardwareFaultFlags)
    }

    @Test
    fun `voidTransaction always reports not-yet-implemented rather than pretending to succeed`() {
        provider.voidTransaction("REF_1", callback)

        assertEquals(0, callback.successCalls)
        assertTrue(callback.failureMessages.single().contains("not yet implemented"))
    }

    @Test
    fun `refundTransaction always reports not-yet-implemented rather than pretending to succeed`() {
        provider.refundTransaction("REF_1", 500, callback)

        assertEquals(0, callback.successCalls)
        assertTrue(callback.failureMessages.single().contains("not yet implemented"))
    }

    @Test
    fun `cancelCurrentTransaction without an attached reader does not throw`() {
        provider.cancelCurrentTransaction()
        // No assertion beyond "didn't throw" -- there is nothing to cancel yet.
    }

    @Test
    fun `stopCardDetection without an attached reader does not throw`() {
        provider.stopCardDetection()
    }

    @Test
    fun `detachReader on a never-attached provider does not throw`() {
        provider.detachReader()
    }
}
