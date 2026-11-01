package com.goldsky.ssp.iris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CashTenderTest {
    @Test fun quickAmountsStartExactThenNextBills() {
        assertEquals(listOf(1_243, 1_500, 2_000, 5_000), CashTender.quickAmounts(1_243))
        assertEquals(listOf(3_710, 4_000, 5_000, 10_000), CashTender.quickAmounts(3_710))
    }

    @Test fun roundTotalDoesNotRepeatItself() {
        assertEquals(listOf(2_000, 5_000, 10_000), CashTender.quickAmounts(2_000))
    }

    @Test fun nothingForZero() = assertEquals(emptyList<Int>(), CashTender.quickAmounts(0))

    @Test fun keypadCapsAtOneHundredThousand() {
        assertEquals(1_234, CashTender.append(123, 4))
        assertNull(CashTender.append(10_000_000, 1))
        assertNull(CashTender.append(Int.MAX_VALUE / 10, 9))
    }
}
