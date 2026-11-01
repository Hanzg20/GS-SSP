package com.goldsky.ssp.payment

import kotlinx.serialization.Serializable

/** Units of one sale line given back (transaction_items.line_no). */
@Serializable
data class RefundLine(val line_no: Int, val quantity: Int)

/**
 * Some items of a sale back (Iris / Ourea, 2026-11-01; whole sales still go
 * through RefundFlow.start). What a unit is worth is what the customer paid for
 * it: its line (already after a line discount), scaled down by an order
 * discount when the sale's subtotal is below its lines, plus tax at the line's
 * rate -- per rate, rounded once (SalesTax). Never more than is left of the
 * sale; tips are never refunded here.
 */
data class PartialRefund(val refundRef: String, val lines: List<RefundLine>, val amountCents: Int, val taxCents: Int) {
    companion object {
        /**
         * [picked] = line_no -> units; lines not in [items] or past what's left to give back are ignored.
         * [subtotalCents]: the sale's, after every discount.
         */
        fun of(items: List<TransactionItem>, picked: Map<Int, Int>, subtotalCents: Int, leftCents: Int, refundRef: String = "RFD-" + System.currentTimeMillis()): PartialRefund? {
            val lines = items.mapNotNull { i ->
                val n = (picked[i.line_no] ?: 0).coerceAtMost(i.quantity - i.refunded_qty)
                if (n > 0 && i.quantity > 0) i to n else null
            }
            if (lines.isEmpty() || leftCents <= 0) return null
            val linesSum = items.sumOf { it.line_total_cents.toLong() }
            fun scaled(c: Int) = if (linesSum > subtotalCents && linesSum > 0) (c.toLong() * subtotalCents / linesSum).toInt() else c
            val nets = lines.map { (i, n) -> scaled(netOf(i, n)) to i.tax_rate_bps }
            val net = nets.sumOf { it.first }
            val tax = SalesTax.cents(nets)
            // The last of the items: exactly what's left, whatever the rounding.
            val all = items.all { i -> i.quantity - i.refunded_qty == (lines.find { it.first === i }?.second ?: 0) }
            val amount = if (all) leftCents else (net + tax).coerceAtMost(leftCents)
            if (amount <= 0) return null
            return PartialRefund(refundRef, lines.map { (i, n) -> RefundLine(i.line_no, n) }, amount, tax.coerceAtMost(amount))
        }

        /** [n] more of [i]'s units; the last unit takes the rounding, so all of them add up to the line. */
        private fun netOf(i: TransactionItem, n: Int): Int {
            val lineNet = i.line_total_cents
            val before = i.refunded_qty
            fun upTo(k: Int) = (lineNet.toLong() * k / i.quantity).toInt()
            return upTo(before + n) - upTo(before)
        }
    }
}

/** Partial refunds so far on a whole sale, for the "what's left" figure. */
fun leftToRefund(amountCents: Int, tipCents: Int, refundedCents: Int): Int = amountCents - tipCents - refundedCents
