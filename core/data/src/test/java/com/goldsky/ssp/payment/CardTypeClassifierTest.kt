package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Test

class CardTypeClassifierTest {
    @Test fun schemeFlagWins() {
        assertEquals("DEBIT_CARD", CardTypeClassifier.paymentMethod("Debit", "A0000000031010"))
        assertEquals("CREDIT_CARD", CardTypeClassifier.paymentMethod("credit", "A0000002771010"))
    }

    @Test fun debitAidsWhenNoScheme() {
        assertEquals("DEBIT_CARD", CardTypeClassifier.paymentMethod(null, "A0000002771010"))
        assertEquals("DEBIT_CARD", CardTypeClassifier.paymentMethod(null, "A000000333010101"))
        assertEquals("DEBIT_CARD", CardTypeClassifier.paymentMethod("", "a0000000043060"))
    }

    @Test fun otherwiseCredit() {
        assertEquals("CREDIT_CARD", CardTypeClassifier.paymentMethod(null, "A0000000031010"))
        assertEquals("CREDIT_CARD", CardTypeClassifier.paymentMethod(null, "A000000333010102"))
        assertEquals("CREDIT_CARD", CardTypeClassifier.paymentMethod(null, null))
    }

    @Test fun debitBrandWithoutAid() {
        // Nuvei SmartPay: CardBrand INTERAC, EmvAid null (TIMER_1791374627643).
        assertEquals("DEBIT_CARD", CardTypeClassifier.paymentMethod(null, null, "INTERAC"))
        assertEquals("DEBIT_CARD", CardTypeClassifier.paymentMethod(null, null, "interac "))
        assertEquals("CREDIT_CARD", CardTypeClassifier.paymentMethod(null, null, "MASTERCARD"))
        assertEquals("CREDIT_CARD", CardTypeClassifier.paymentMethod("credit", null, "INTERAC"))
    }

    @Test fun failedSaleStatus() {
        assertEquals("CANCELLED", TransactionRepository.failedCardSaleStatus(cancelled = true))
        assertEquals("DECLINED", TransactionRepository.failedCardSaleStatus(cancelled = false))
    }
}
