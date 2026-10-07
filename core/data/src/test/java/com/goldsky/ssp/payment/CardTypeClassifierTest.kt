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

    @Test fun realAuthCode() {
        assertEquals("07582Z", TransactionRepository.realAuthCode(" 07582Z "))
        assertEquals(null, TransactionRepository.realAuthCode("OK")) // provider stand-in
        assertEquals(null, TransactionRepository.realAuthCode(""))
        assertEquals(null, TransactionRepository.realAuthCode(null))
    }

    @Test fun duplicateInsertCountsAsRecorded() {
        assertEquals(true, TransactionRepository.isAlreadyRecorded(
            "duplicate key value violates unique constraint \"transactions_ecr_ref_num_key\""))
        assertEquals(false, TransactionRepository.isAlreadyRecorded("Communication Timeout"))
        assertEquals(false, TransactionRepository.isAlreadyRecorded(null))
    }

    @Test fun scannerStartFailure() {
        assertEquals(true, DiagnosticManager.isScannerStartFailure("Hardware initialization failed: x", 5_000))
        assertEquals(true, DiagnosticManager.isScannerStartFailure("Internal error: Unknown camera ID", 5_000))
        assertEquals(true, DiagnosticManager.isScannerStartFailure("Scan error: -1", 40))   // camera page never showed
        assertEquals(false, DiagnosticManager.isScannerStartFailure("Scan error: -1", 8_000)) // customer closed it
    }
}
