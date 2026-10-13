package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Test

class DineSplitTest {

    private fun item(id: String, cents: Int, qty: Int = 1, bps: Int = 1300, paid: String? = null, status: String = "SENT") =
        DineRepository.Item(id = id, check_id = "c", round = 1, name = id, quantity = qty, unit_price_cents = cents,
            tax_rate_bps = bps, paid_ecr_ref_num = paid, status = status)

    private fun detail(items: List<DineRepository.Item>, paid: List<Int> = emptyList(), paidTax: List<Int> = emptyList()) = DineRepository.CheckDetail(
        DineRepository.Check("c", "t", 1, "OPEN"), null, items,
        paid.mapIndexed { i, a -> DineRepository.Payment("p$i", "c", "R$i", a, tax_cents = paidTax.getOrElse(i) { 0 }) }
    )

    @Test
    fun `split payments add up to the check's tax`() {
        // The T5 check of 2026-10-13: noodle (13%) paid alone, then the rest (two
        // a zero-rated drink and two taxed ones) in two equal shares. Proportional-to-total tax
        // reported $3.08 for a $2.86 check.
        val items = listOf(item("noodle", 1449), item("tea", 599), item("soy", 349, bps = 0), item("herbal", 149))
        val d0 = detail(items)
        assertEquals(2832, d0.totalCents); assertEquals(286, d0.taxCents)
        val t1 = DineRepository.taxOfPayment(d0, 1637, listOf(1449 to 1300))
        assertEquals(188, t1)
        val d1 = detail(items.map { if (it.id == "noodle") it.copy(paid_ecr_ref_num = "R0") else it }, paid = listOf(1637), paidTax = listOf(t1))
        val t2 = DineRepository.taxOfPayment(d1, 598, null)
        val d2 = detail(d1.items, paid = listOf(1637, 598), paidTax = listOf(t1, t2))
        val t3 = DineRepository.taxOfPayment(d2, d2.balanceCents, null)
        assertEquals(286, t1 + t2 + t3)
    }

    @Test
    fun `total is food plus tax once per rate, void lines out`() {
        val d = detail(listOf(item("noodle", 1449, qty = 2), item("dumpling", 899), item("tea", 599), item("x", 999, status = "VOID")))
        assertEquals(4396, d.subtotalCents)
        assertEquals(571, d.taxCents)   // 4396 * 13% = 571.48
        assertEquals(4967, d.totalCents)
    }

    @Test
    fun `split by items charges those lines with their tax, last lines take the balance`() {
        val d = detail(listOf(item("a", 1000), item("b", 1001), item("c", 1001)))
        // total: 3002 + 390.26 -> 390 = 3392
        assertEquals(1130, DineRepository.amountForItems(d, listOf("a")))   // 1000 + 130
        val afterA = detail(listOf(item("a", 1000, paid = "R0"), item("b", 1001), item("c", 1001)), paid = listOf(1130))
        // b + c alone would be 2002 + 260.26 = 2262; the balance is 3392 - 1130 = 2262 too, but the
        // last lines always take the balance exactly.
        assertEquals(afterA.balanceCents, DineRepository.amountForItems(afterA, listOf("b", "c")))
        assertEquals(0, DineRepository.amountForItems(afterA, listOf("a")))   // already paid
    }

    @Test
    fun `equal shares add up to the balance`() {
        val start = 4967
        var balance = start
        val shares = mutableListOf<Int>()
        for (left in 3 downTo 1) {
            val s = DineRepository.equalShare(balance, start, 3, left)
            shares += s; balance -= s
        }
        assertEquals(listOf(1656, 1656, 1655), shares)
        assertEquals(0, balance)
    }
}
