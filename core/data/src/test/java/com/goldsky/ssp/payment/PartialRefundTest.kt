package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartialRefundTest {
    private fun item(line: Int, qty: Int, total: Int, bps: Int = 1300, back: Int = 0) =
        TransactionItem("S", "SN", line, name = "i$line", quantity = qty, unit_price_cents = total / qty,
            line_total_cents = total, tax_rate_bps = bps, refunded_qty = back)

    // $3.00 x 2 taxed + $5.00 exempt = $11.00 + $0.78 HST = $11.78
    private val items = listOf(item(1, 2, 600), item(2, 1, 500, bps = 0))

    @Test fun oneTaxedUnitWithItsTax() {
        val r = PartialRefund.of(items, mapOf(1 to 1), subtotalCents = 1100, leftCents = 1178, refundRef = "R")!!
        assertEquals(339, r.amountCents)   // 300 + 39
        assertEquals(39, r.taxCents)
        assertEquals(listOf(RefundLine(1, 1)), r.lines)
    }

    @Test fun exemptItemHasNoTax() {
        val r = PartialRefund.of(items, mapOf(2 to 1), 1100, 1178)!!
        assertEquals(500, r.amountCents); assertEquals(0, r.taxCents)
    }

    @Test fun everythingLeftIsExactlyWhatIsLeft() {
        val r = PartialRefund.of(items, mapOf(1 to 2, 2 to 1), 1100, 1178)!!
        assertEquals(1178, r.amountCents)
    }

    @Test fun cannotPickMoreThanIsLeftOfALine() {
        val back = listOf(item(1, 2, 600, back = 1), item(2, 1, 500, bps = 0))
        val r = PartialRefund.of(back, mapOf(1 to 5), 1100, 1178 - 339)!!
        assertEquals(listOf(RefundLine(1, 1)), r.lines)
        assertEquals(339, r.amountCents)
    }

    @Test fun orderDiscountScalesTheRefundDown() {
        // Lines $11.00, sale subtotal $8.80 after a 20% order discount.
        val r = PartialRefund.of(items, mapOf(2 to 1), subtotalCents = 880, leftCents = 942)!!
        assertEquals(400, r.amountCents)
    }

    @Test fun nothingPickedOrNothingLeft() {
        assertNull(PartialRefund.of(items, emptyMap(), 1100, 1178))
        assertNull(PartialRefund.of(items, mapOf(1 to 1), 1100, 0))
    }

    @Test fun dayReportCountsPartialRefunds() {
        val rows = listOf(
            ReportTxn("A", 1178, 1100, 78, 0, "PAID", "CASH", "mark", "2026-11-01T12:00:00", "2026-11-01T13:00:00", "boss", refunded_cents = 339),
            ReportTxn("B", 500, 500, 0, 0, "PAID", "CREDIT_CARD", "mark", "2026-11-01T12:30:00")
        )
        val r = DayReport.build(rows, emptyList(), { it.startsWith("2026-11-01") })
        assertEquals(1678, r.sales.cents)
        assertEquals(339, r.refundCents)
        assertEquals(1678 - 339, r.netCents)
        assertEquals(1178 - 339, r.expectedCashCents)
        assertEquals("PARTIAL", r.refunds.single().status)
    }
}
