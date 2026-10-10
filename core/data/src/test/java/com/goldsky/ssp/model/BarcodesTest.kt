package com.goldsky.ssp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodesTest {
    @Test
    fun `UPC-A and its EAN-13 form are the same code`() {
        assertEquals("0012345678905", Barcodes.canonical("012345678905"))
        assertEquals("0012345678905", Barcodes.canonical("0012345678905"))
    }

    @Test
    fun `spaces and dashes are dropped, other codes kept`() {
        assertEquals("2000010000012", Barcodes.canonical(" 2000010-000012 "))
        assertEquals("ABC123", Barcodes.canonical("ABC123"))
        assertNull(Barcodes.canonical("  "))
    }
}
