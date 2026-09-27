package com.goldsky.ssp.payment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionRepositoryUuidTest {
    @Test
    fun acceptsRealProductIds() {
        // Real products.id values seen on bay5 (wash $4, vacuum package).
        assertTrue(TransactionRepository.isUuid("7660d252-ffef-4bc4-a18c-c718f0f661fa"))
        assertTrue(TransactionRepository.isUuid("1484A7F4-F08C-4479-89CD-A1A4935A3C7E"))
    }

    @Test
    fun rejectsNonUuids() {
        assertFalse(TransactionRepository.isUuid("wash_basic"))
        assertFalse(TransactionRepository.isUuid("7660d252-ffef-c718f0f661fa"))
        assertFalse(TransactionRepository.isUuid(""))
    }
}
