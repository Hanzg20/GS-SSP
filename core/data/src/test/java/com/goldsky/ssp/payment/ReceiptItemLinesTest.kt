package com.goldsky.ssp.payment

import com.goldsky.ssp.model.SaleLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptItemLinesTest {

    private val rice = SaleLine("大米 Jasmine Rice 8lb", 1, 1299, 0)
    private val crackers = SaleLine("虾片 Prawn Crackers", 2, 299, 1300)

    @Test
    fun `no lines prints nothing`() {
        assertTrue(ReceiptPrinterManager.itemLines(emptyList()).isEmpty())
    }

    @Test
    fun `taxed lines are marked H and HST is broken out`() {
        val out = ReceiptPrinterManager.itemLines(listOf(rice, crackers))
        assertEquals(
            listOf(
                "大米 Jasmine Rice 8lb",
                "  1 x 12.99              12.99  ",
                "虾片 Prawn Crackers",
                "  2 x 2.99                5.98 H",
                "--------------------------------",
                "HST 13% on 5.98           0.78  ",
                "Tax exempt items         12.99  ",
                "H = taxable item"
            ),
            out
        )
        // Every column-aligned row is exactly the paper width.
        out.filter { it.startsWith("  ") || it.startsWith("HST") || it.startsWith("Tax") }.forEach { assertEquals(it, 32, it.length) }
    }

    @Test
    fun `items map to numbered rows and drop non-uuid product ids`() {
        val items = TransactionRepository.toItems(
            "RET-1", "SN1",
            listOf(rice.copy(productId = "local-7"), crackers.copy(productId = "3c8e86c7-4c6d-4d1a-aa15-90aeed7aae76"))
        )
        assertEquals(listOf(1, 2), items.map { it.line_no })
        assertEquals(null, items[0].product_id)
        assertEquals("3c8e86c7-4c6d-4d1a-aa15-90aeed7aae76", items[1].product_id)
        assertEquals(598, items[1].line_total_cents)
    }
}
