package com.goldsky.ssp.payment

import com.goldsky.ssp.payment.hardware.IPaymentProvider.OfflineUpload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCardManagerTest {
    private fun decide(enabled: Boolean = true, supported: Boolean = true, online: Boolean = false, amount: Int = 2000, waiting: Int = 0) =
        OfflineCardManager.decide(enabled, supported, online, amount, limitCents = 5000, waiting = waiting, max = 10)

    @Test fun onlyWhenOnAndOfflineAndWithinLimits() {
        assertTrue(decide())
        assertFalse(decide(enabled = false))
        assertFalse(decide(supported = false))
        assertFalse(decide(online = true))
        assertFalse(decide(amount = 5001))
        assertTrue(decide(amount = 5000))
        assertFalse(decide(waiting = 10))
    }

    private val a = OfflineCardManager.Pending("A", 100, 0)
    private val b = OfflineCardManager.Pending("B", 200, 0)

    @Test fun listedResults() {
        val (sent, refused) = OfflineCardManager.classify(listOf(a, b), OfflineUpload.Done(listOf("A"), listOf("B"), false, ""))
        assertEquals(listOf(a), sent); assertEquals(listOf(b), refused)
    }

    @Test fun allSentSettlesEveryoneButTheRefused() {
        val (sent, refused) = OfflineCardManager.classify(listOf(a, b), OfflineUpload.Done(emptyList(), emptyList(), true, ""))
        assertEquals(listOf(a, b), sent); assertTrue(refused.isEmpty())
    }

    @Test fun unclearStaysWaiting() {
        val (sent, refused) = OfflineCardManager.classify(listOf(a, b), OfflineUpload.Done(emptyList(), emptyList(), false, ""))
        assertTrue(sent.isEmpty() && refused.isEmpty())
    }
}
