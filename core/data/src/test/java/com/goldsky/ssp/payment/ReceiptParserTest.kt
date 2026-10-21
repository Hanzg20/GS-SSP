package com.goldsky.ssp.payment

import com.goldsky.ssp.payment.ReceiptParser.Flag
import com.goldsky.ssp.payment.ReceiptParser.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptParserTest {
    private val today = "2026-10-20"
    private fun parse(vararg rows: String) = ReceiptParser.parseRows(rows.toList(), today)

    @Test
    fun `supermarket receipt folded head to foot`() {
        val f = parse(
            "FRESHCO",
            "1234 Bank St, Ottawa ON",
            "HST# 123456789 RT0001",
            "10/16/2026 14:32",
            // (folded: the items are behind)
            "SUBTOTAL 40.00",
            "HST 13% 5.20",
            "TOTAL 45.20",
            "VISA 45.20",
            "TOTAL SAVINGS 3.10",
        )
        assertEquals("FRESHCO", f.vendor)
        assertEquals("2026-10-16", f.date)
        assertEquals(4520, f.totalCents)
        assertEquals(520, f.taxCents)
        assertEquals("123456789 RT 0001", f.hstNumber)
        assertTrue(f.flags.isEmpty())
    }

    @Test
    fun `cash and change are never the total`() {
        val f = parse("T&T SUPERMARKET", "2026-10-15", "SUBTOTAL 18.50", "GST 0.93", "PST 0.00", "TOTAL 19.43", "CASH 50.00", "CHANGE 30.57")
        assertEquals(1943, f.totalCents)
        assertEquals(93, f.taxCents)
    }

    @Test
    fun `chinese receipt`() {
        val f = parse("大华超市 Great Wall", "2026年10月12日 18:05", "小计 30.00", "税 HST 3.90", "合计 33.90", "现金 40.00", "找零 6.10")
        assertEquals("大华超市 Great Wall", f.vendor)
        assertEquals("2026-10-12", f.date)
        assertEquals(3390, f.totalCents)
        assertEquals(390, f.taxCents)
        assertEquals(3000, f.subtotalCents)
    }

    @Test
    fun `amount on the line under the word`() {
        val f = parse("COSTCO WHOLESALE", "Oct 3, 2026", "SUBTOTAL", "212.40", "TAX", "27.61", "**** TOTAL", "240.01")
        assertEquals(24001, f.totalCents)
        assertEquals("2026-10-03", f.date)
        assertEquals(21240, f.subtotalCents)
    }

    @Test
    fun `split price column is put back on its row`() {
        // ML Kit read the words and the prices as two blocks.
        val lines = listOf(
            OcrLine("GIANT TIGER", 40, 10, 300, 40),
            OcrLine("SUBTOTAL", 20, 200, 160, 224), OcrLine("HST", 20, 230, 80, 254), OcrLine("TOTAL", 20, 260, 120, 286),
            OcrLine("10.00", 400, 202, 470, 226), OcrLine("1.30", 410, 232, 470, 256), OcrLine("11.30", 400, 262, 470, 288),
        )
        val rows = ReceiptParser.rows(lines)
        assertEquals(listOf("GIANT TIGER", "SUBTOTAL  10.00", "HST  1.30", "TOTAL  11.30"), rows)
        val f = ReceiptParser.parse(lines, today)
        assertEquals(1130, f.totalCents)
        assertEquals(130, f.taxCents)
    }

    @Test
    fun `total tax line and total including tax`() {
        val f = parse("HOME DEPOT", "10/01/26", "SUBTOTAL 100.00", "TOTAL TAX 13.00", "TOTAL (INCL. HST) 113.00")
        assertEquals(11300, f.totalCents)
        assertEquals(1300, f.taxCents)
        assertTrue(Flag.HST_NUMBER_MISSING in f.flags)
    }

    @Test
    fun `missing pieces are flagged, not guessed`() {
        val f = parse("CORNER STORE", "THANK YOU")
        assertNull(f.totalCents); assertNull(f.taxCents); assertNull(f.date)
        assertTrue(f.flags.containsAll(listOf(Flag.TOTAL_MISSING, Flag.TAX_MISSING, Flag.DATE_MISSING)))
    }

    @Test
    fun `numbers that do not add up are flagged`() {
        val f = parse("SHOP", "2026-10-19", "SUBTOTAL 50.00", "HST 6.50", "TOTAL 65.00")
        assertTrue(Flag.TOTAL_MISMATCH in f.flags)
        val g = parse("SHOP", "2026-10-19", "HST 30.00", "TOTAL 40.00")
        assertTrue(Flag.TAX_ODD in g.flags)
    }

    @Test
    fun `large purchase is flagged`() {
        val f = parse("RESTAURANT DEPOT", "HST 123456789RT0001", "2026-10-18", "HST 65.00", "TOTAL 565.00")
        assertTrue(Flag.LARGE_PURCHASE in f.flags)
        assertTrue(Flag.HST_NUMBER_MISSING !in f.flags)
    }

    @Test
    fun `dates - future and too old are not taken, day-month read when month-day is impossible`() {
        assertNull(ReceiptParser.findDate("2026-11-01", today))
        assertNull(ReceiptParser.findDate("2024-01-05", today))
        assertEquals("2026-10-13", ReceiptParser.findDate("13/10/2026", today))
        // Both readings valid: the one closer to today.
        assertEquals("2026-10-05", ReceiptParser.findDate("05/10/2026", today))
    }

    @Test
    fun `rates and negative amounts are not money`() {
        assertEquals(listOf(520), ReceiptParser.amounts("HST 13.00% 5.20"))
        assertEquals(listOf(123456), ReceiptParser.amounts("TOTAL $1,234.56"))
        assertEquals(emptyList<Int>(), ReceiptParser.amounts("DISCOUNT -3.00"))
    }

    @Test
    fun `the model only fills what the rules missed, then everything is checked again`() {
        val base = parse("SHOP", "2026-10-19", "HST 1.30")              // no total found
        val ai = ReceiptParser.Fields("Other Name", "2026-10-01", 1130, 999, null, null, emptySet())
        val m = ReceiptParser.merge(base, ai)
        assertEquals("SHOP", m.vendor)            // the rules' own reading stays
        assertEquals("2026-10-19", m.date)
        assertEquals(1130, m.totalCents)          // filled
        assertEquals(130, m.taxCents)             // the rules' tax, not the model's
        assertTrue(Flag.AI_FILLED in m.flags)
        assertTrue(Flag.TOTAL_MISSING !in m.flags)
        // Nothing new from the model: not marked.
        assertTrue(Flag.AI_FILLED !in ReceiptParser.merge(base, ReceiptParser.Fields(null, null, null, null, null, null, emptySet())).flags)
    }

    // ---- four real receipts photographed on the Q2, 2026-10-20 (OCR text as read) ----

    private fun real(text: String) = ReceiptParser.parseRows(text.trimIndent().lines(), today)

    @Test
    fun `real - Home Hardware`() {
        val f = real("""
            HOME  How  doers
            KANATA ON. NOW HIRING, APPLY WITHIN.
            7108 00062 19489 20/10/26 04:56 p.m.
            881599018221 CDD04540101F <A>  4.98
            RSN: 2 NEW AMT 5.00 MKDN  -9.98
            「MAX REFUND VALUE %25.00
            SUBTOTAL  38.94
            GST/HST  5.06
            TOTAL  ${'$'}44.00
            CAD${'$'} 44.00
            13% HST R135772911
            A  1  90  18/01/2027
        """)
        assertEquals(4400, f.totalCents); assertEquals(506, f.taxCents); assertEquals("2026-10-20", f.date)
        assertTrue(Flag.REFUND !in f.flags)   // "MAX REFUND VALUE" is a return policy, not a refund
    }

    @Test
    fun `real - Walmart`() {
        val f = real("""
            Walmart次
            How did we do today?
            CA PSSPRT  681131981410  ${'$'}18.57 J
            SUBTOTAL  ${'$'}18.57
            HST 13,0000 %  ${'$'}2.41
            TOTAL  ${'$'}20.98
            CASH TEND  ${'$'}40.00
            CHANGE DUE  ${'$'}19.00
            GST/HST 137466199 RT 0001
            QST 1016651 356 TQ 0001
            # ITEMS SOLD 1
        """)
        assertEquals(2098, f.totalCents); assertEquals(241, f.taxCents); assertEquals("137466199 RT 0001", f.hstNumber)
        assertTrue(Flag.TOTAL_MISMATCH !in f.flags)
    }

    @Test
    fun `real - Costco, O read as 0`() {
        val f = real("""
            CoSTCO
            三WHOLESALE
            669 5 DOZ EgG6  18.29
            SUBTOTAL  18.29
            TAX  0.00
            冰家米淋T0TAL  18.29
            AUTH #: 5375Z  2026/10/20 16:08:45
            AMOUNT: 18.29
            CHANGE  0.00
            TOTAL TAX  0.00
            TOTAL NUMBER OF ITEMS SOLD  1
        """)
        assertEquals(1829, f.totalCents); assertEquals(0, f.taxCents); assertEquals("2026-10-20", f.date)
    }

    @Test
    fun `real - Sephora return is a refund, dated by its own stamp`() {
        val f = real("""
            SEPHORA
            RE.TURN WITH RECEIPI
            MBM BLSH VEIL PNK PNY  -48.00
            RECEIPTED RETURN
            ORIG DATE  10/2/26
            SUBTOTAL  -48.00
            CANADA GST/TPS  5%  -2.40
            ONTARIO HST  8%  -3.84
            TOTAL AMT  -54.24
            WeChat Refund  (${'$'}54.24)
            Trans Type  REFUND
            Amount  (${'$'}54.24)
            10/10/26 18:52  2304 2 245026 65838
        """)
        assertTrue(Flag.REFUND in f.flags)
        assertNull(f.totalCents)
        assertEquals("2026-10-10", f.date)
        // The model can't turn it into an expense either.
        assertNull(ReceiptParser.merge(f, ReceiptParser.Fields(null, null, 5424, null, null, null, emptySet())).totalCents)
    }

    // ---- second round on the Q2, 2026-10-20 ----

    @Test
    fun `real - Geelano, NON TAX item and Sep 14 slash 2026`() {
        val f = real("""
            GEELANO INTERNA TIIONAL GROCERY
            14:58:09 #174280 3
            Sep 14/2028  Clerk: DANIEL Cash ier : DANIEL  Amount
            Clic Light Red KjdneyBeans 2 10.49  GROCERY NON TAX  fresh onions 5 lb
            48.43
            Iten Count :7  Sublota) :  0.43
            HST:
            48.86
            Total due:  48.86
            Sep 14/2026  Card Mmber :祥ト本9962  Card Iype : Mas tercard
            Author ization# : 042042  Host Da te : 14 Sep 2026  Host Time : 14:58: 06
        """)
        assertEquals(4886, f.totalCents)
        assertNull(f.taxCents)                 // not 10.49 off an item row, not the total
        assertTrue(Flag.TAX_MISSING in f.flags)
        assertEquals("2026-09-14", f.date)
    }

    @Test
    fun `real - Shoppers, yy slash mm slash dd`() {
        val f = real("""
            SHOPPERS  DRUG MART
            RICOLA MOUN HE  SUBTOTAL:
            1.30
            HST:
            TOTAL:
            1 Itom  11.29
            787257211 RTO003
            REFERENCE #  26/07/02 21:45:23
        """)
        assertEquals(1129, f.totalCents)
        assertEquals("2026-07-02", f.date)
    }

    @Test
    fun `real - a tax line holding the total is not the tax`() {
        val f = real("""
            STORE #  734
            REG: 2. 99  SUB-TOTAL  1.69
            HST TAX  14,68
            TOTAL  14.68
        """)
        assertEquals(1468, f.totalCents)
        assertNull(f.taxCents)
    }

    @Test
    fun `subtotal and total give the tax when no tax line was read`() {
        val f = parse("SHOP", "2026-10-19", "SUBTOTAL 40.00", "TOTAL 45.20")
        assertEquals(520, f.taxCents)
    }

    @Test
    fun `real - blurred full-page invoice is unreadable, not guessed`() {
        val f = real("""
            ENCEL AUTO TECH n  NCHCE
            sericeecela
            HEOK NSPECT AND DAG BRAKES OVER HEATING-RRJC
            DORA REMOVE & REPLACE REAR PADS AND ROTORS c)
        """)
        assertTrue(Flag.UNREADABLE in f.flags)
        assertNull(f.totalCents)
    }
}
