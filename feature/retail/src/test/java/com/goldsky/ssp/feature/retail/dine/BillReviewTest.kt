package com.goldsky.ssp.feature.retail.dine

import com.goldsky.ssp.model.SaleLine
import com.goldsky.ssp.payment.DineRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillReviewTest {
    private val check = DineRepository.Check("c1", "t1", 6, "OPEN")
    private fun item(id: String, name: String, cents: Int, qty: Int = 1, paid: String? = null, options: List<String> = emptyList()) =
        DineRepository.Item(id, "c1", 1, name = name, options = options, quantity = qty, unit_price_cents = cents, tax_rate_bps = 1300, paid_ecr_ref_num = paid)

    private val items = listOf(item("a", "Pork Dumplings", 1095), item("b", "Noodle", 1295, options = listOf("Mild")), item("c", "Coke", 250, qty = 2))
    // 1095 + 1295 + 500 = 2890, 13% = 375.7 -> 376, total 3266
    private val detail = DineRepository.CheckDetail(check, null, items, listOf(DineRepository.Payment("p1", "c1", "OUR-1", 1000)))

    @Test
    fun `the balance shows the whole bill, what was paid and what is due`() {
        val pay = CheckPay("c1", detail.balanceCents, null, emptyList(), "", "T1", 6, taxCents = 260)
        val r = pay.billReview(detail, "Table T1 · #6")
        assertEquals(3, r.lines.size)
        assertEquals("Noodle (Mild)", r.lines[1].name)
        assertEquals(2890, r.subtotalCents)
        assertEquals(detail.totalCents, r.totalCents)
        assertEquals(1000, r.paidCents)
        assertEquals(detail.balanceCents, r.dueCents)
        assertNull(r.dueLabel) // the balance: "Balance due"
        assertEquals(r.totalCents - r.paidCents, r.dueCents) // every figure adds up
    }

    @Test
    fun `an even share is labelled as such`() {
        val pay = CheckPay("c1", 1133, null, emptyList(), "Share 1/2", "T1", 6, taxCents = 130)
        val r = pay.billReview(detail, "Table T1 · #6")
        assertEquals(1133, r.dueCents)
        assertEquals("Share 1/2", r.dueLabel)
    }

    @Test
    fun `paying chosen dishes lists just those`() {
        val lines = listOf(SaleLine("Coke", 2, 250, 1300))
        val pay = CheckPay("c1", 565, listOf("c"), lines, "Items", "T1", 6, taxCents = 65)
        val r = pay.billReview(detail, "Table T1 · #6")
        assertEquals("Table T1 · #6 · Items", r.title)
        assertEquals(1, r.lines.size)
        assertEquals(500, r.subtotalCents)
        assertEquals(565, r.totalCents)
        assertEquals(0, r.paidCents)
        assertEquals(565, r.dueCents)
    }
}
