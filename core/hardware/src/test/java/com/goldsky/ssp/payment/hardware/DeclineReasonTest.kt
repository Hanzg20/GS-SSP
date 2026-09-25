package com.goldsky.ssp.payment.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class DeclineReasonTest {
    @Test
    fun classifiesProviderMessagesSeenOnTheQ3mini() {
        assertEquals(DeclineReason.CANCELLED, DeclineReason.classify("Payment Error: cancelled by user (-139)", false))
        assertEquals(DeclineReason.UNAVAILABLE, DeclineReason.classify("Communication Timeout (P3)", true))
        assertEquals(DeclineReason.DECLINED, DeclineReason.classify("Payment Error: Do Not Honor (05)", false))
        // A hardware fault wins even if the text looks like a cancel.
        assertEquals(DeclineReason.UNAVAILABLE, DeclineReason.classify("cancelled by user (-139)", true))
    }
}
