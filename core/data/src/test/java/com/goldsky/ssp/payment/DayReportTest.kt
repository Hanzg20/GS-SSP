package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Test

class DayReportTest {

    private val today = "2026-10-10"
    private fun inDay(ts: String) = ts.startsWith(today)

    private fun txn(ref: String, amount: Int, method: String, status: String = "PAID", staff: String? = "mark",
                    created: String = "${today}T12:00:00", refunded: String? = null, tax: Int = 0, tip: Int = 0, approver: String? = null) =
        ReportTxn(ref, amount, amount - tax - tip, tax, tip, status, method, staff, created, refunded, approver)

    @Test
    fun `sales by method, staff, net and expected cash`() {
        val rows = listOf(
            txn("A", 1130, "CASH", tax = 130),
            txn("B", 2000, "CREDIT_CARD", staff = "tom"),
            txn("C", 500, "CASH", status = "REFUNDED", refunded = "${today}T15:00:00", approver = "an'ni"),
            txn("D", 300, "DEBIT_CARD", status = "DECLINED"),                                   // not collected
            txn("E", 900, "CREDIT_CARD", status = "VOIDED", created = "2026-10-09T18:00:00",
                refunded = "${today}T09:00:00", staff = "tom")                                   // yesterday's sale refunded today
        )
        val r = DayReport.build(rows, emptyList(), ::inDay)

        assertEquals(DayReport.Totals(3, 3630), r.sales)                  // A, B, C
        assertEquals(DayReport.Totals(2, 1630), r.byMethod["CASH"])
        assertEquals(DayReport.Totals(1, 2000), r.byMethod["CREDIT_CARD"])
        assertEquals(1400, r.refundCents)                                 // C + E
        assertEquals(2230, r.netCents)
        assertEquals(1130, r.expectedCashCents)                           // 1630 cash in - 500 cash back
        assertEquals(listOf("E", "C"), r.refunds.map { it.ref })          // by refund time
        assertEquals("an'ni", r.refunds[1].approvedBy)

        val mark = r.byStaff.getValue("mark")
        assertEquals(DayReport.StaffTotals(2, 1630, 1630, 0, 500), mark)
        val tom = r.byStaff.getValue("tom")
        assertEquals(DayReport.StaffTotals(1, 2000, 0, 2000, 900), tom)
        assertEquals(130, r.taxCents)
    }

    @Test
    fun `taxable and exempt come from the lines, falling back to the sale`() {
        val rows = listOf(
            txn("A", 1626, "CASH", tax = 39),      // lines: 1299 exempt + 299 taxable (+ tax)
            txn("B", 1130, "CASH", tax = 130),     // no lines, taxed -> taxable 1000
            txn("C", 500, "CASH")                  // no lines, untaxed -> exempt 500
        )
        val items = listOf(ReportItemRow("A", 1299, 0), ReportItemRow("A", 299, 1300))
        val r = DayReport.build(rows, items, ::inDay)
        assertEquals(299 + 1000, r.taxableCents)
        assertEquals(1299 + 500, r.exemptCents)
    }

    @Test
    fun `sale-date basis counts refunds of the day's sales only`() {
        val rows = listOf(
            txn("C", 500, "CASH", status = "REFUNDED", refunded = "2026-10-11T09:00:00"),
            txn("E", 900, "CREDIT_CARD", status = "VOIDED", created = "2026-10-09T18:00:00", refunded = "${today}T09:00:00")
        )
        val r = DayReport.build(rows, emptyList(), ::inDay, DayReport.Basis.SALE_DATE)
        assertEquals(listOf("C"), r.refunds.map { it.ref })
        assertEquals(0, r.netCents)
    }

    @Test
    fun `timestamps parse as instants, so a Toronto evening sale stays in its local day`() {
        val tz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Toronto"))
            // 21:30 EDT on Oct 10 = 01:30 UTC on Oct 11
            val ms = DayReportRepository.parseIsoMillis("2026-10-11T01:30:00.123456+00:00")!!
            val (start, end) = DayReportRepository.dayBounds(
                java.util.GregorianCalendar(2026, java.util.Calendar.OCTOBER, 10, 12, 0).timeInMillis)
            assertEquals(true, ms in start until end)
            assertEquals(1791682200123L, ms)
            assertEquals(null, DayReportRepository.parseIsoMillis("not a time"))
        } finally {
            java.util.TimeZone.setDefault(tz)
        }
    }

    @Test
    fun `Ourea Scan online payments count as sales, not as cash or card`() {
        val rows = listOf(
            txn("A", 1130, "CASH", tax = 130),
            txn("NUVEI-1", 2694, "ONLINE", staff = "Ourea Scan", tax = 285, tip = 219)   // dine_online_sales row
        )
        val r = DayReport.build(rows, emptyList(), ::inDay)

        assertEquals(DayReport.Totals(2, 3824), r.sales)
        assertEquals(DayReport.Totals(1, 2694), r.byMethod["ONLINE"])
        assertEquals(1130, r.expectedCashCents)                           // the drawer doesn't see online money
        assertEquals(DayReport.StaffTotals(1, 2694, 0, 0, 0), r.byStaff.getValue("Ourea Scan"))
        assertEquals(415, r.taxCents)
        assertEquals(219, r.tipCents)
        assertEquals(1000 + 2190, r.taxableCents)
    }
}
