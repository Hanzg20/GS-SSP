package com.goldsky.ssp.payment

import com.goldsky.ssp.payment.hardware.CardBrands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardBrandsTest {

    @Test
    fun reportedBrandWins_normalisedToUppercase() {
        assertEquals("MASTERCARD", CardBrands.brand("MasterCard", "A0000000031010"))
    }

    @Test
    fun emptyReportedBrandFallsBackToAid() {
        // What the WizarPOS emulator sent on 2026-09-24: CardBrand "", AID A0000000041010.
        assertEquals("MASTERCARD", CardBrands.brand("", "A0000000041010"))
        assertEquals("VISA", CardBrands.brand(null, "A0000000031010"))
        assertEquals("INTERAC", CardBrands.brand("null", "A0000002771010"))
        assertEquals("AMEX", CardBrands.brand(null, "a00000002501"))
    }

    @Test
    fun unknownOrMissingAidGivesNull() {
        assertNull(CardBrands.brand(null, null))
        assertNull(CardBrands.brand(null, "A0000009999999"))
        assertNull(CardBrands.brand(null, "A00000"))
    }

    @Test
    fun wizarPosEntryModes() {
        assertEquals("CONTACTLESS", CardBrands.wizarPosEntryMode("7"))
        assertEquals("CHIP", CardBrands.wizarPosEntryMode("5"))
        assertEquals("CHIP", CardBrands.wizarPosEntryMode("149")) // 0x95
        assertEquals("SWIPE", CardBrands.wizarPosEntryMode("0x90"))
        assertEquals("STORED_VALUE", CardBrands.wizarPosEntryMode("153")) // 0x99
        assertEquals("UNKNOWN_0", CardBrands.wizarPosEntryMode("0"))
        assertEquals("UNKNOWN", CardBrands.wizarPosEntryMode(null))
    }
}
