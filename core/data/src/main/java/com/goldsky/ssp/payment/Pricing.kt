package com.goldsky.ssp.payment

import kotlinx.serialization.Serializable

/**
 * A discount staff put on a line or on the whole order: a percentage ([percentBps],
 * 1000 = 10%) or a fixed amount ([amountCents]), exactly one of the two.
 * [presetId] = a store preset from Cael (discount_presets); null = typed in at
 * the counter. [reason] / [approvedBy] are what the reports and the audit show.
 */
@Serializable
data class Discount(
    val label: String,
    val percentBps: Int? = null,
    val amountCents: Int? = null,
    val presetId: String? = null,
    val reason: String? = null,
    val approvedBy: String? = null,
) {
    init {
        require((percentBps == null) != (amountCents == null)) { "a discount is a percentage or an amount" }
        require((percentBps ?: 0) in 0..10_000 && (amountCents ?: 0) >= 0) { "discount out of range" }
    }

    /** What it takes off [baseCents]: a percentage rounded half up, an amount capped at the base. */
    fun offCents(baseCents: Int): Int = when {
        percentBps != null -> roundHalfUp(baseCents.toLong() * percentBps, 10_000L)
        else -> minOf(amountCents ?: 0, baseCents)
    }.coerceIn(0, baseCents)
}

/** A discount as recorded on the sale (transactions.discounts, receipts, reports). */
@Serializable
data class AppliedDiscount(
    val scope: String,          // LINE | ORDER
    val label: String,
    val amount_cents: Int,
    val line_no: Int? = null,
    val percent_bps: Int? = null,
    val preset_id: String? = null,
    val reason: String? = null,
    val approved_by: String? = null,
) {
    companion object {
        fun of(d: Discount, scope: String, amountCents: Int, lineNo: Int? = null) = AppliedDiscount(
            scope = scope, label = d.label, amount_cents = amountCents, line_no = lineNo,
            percent_bps = d.percentBps, preset_id = d.presetId, reason = d.reason, approved_by = d.approvedBy,
        )
    }
}

/**
 * Prices a sale with discounts, the way Canadian POS systems do it and GST/HST is
 * charged: line discounts first, then the order discount spread over the lines in
 * proportion to what each still costs, then tax on what is left -- per rate,
 * rounded once (SalesTax). A tax-exempt line takes its share of an order discount
 * like any other, so the exempt part shrinks too and no tax is charged on money
 * the customer didn't pay.
 */
object Pricing {

    data class Line(val grossCents: Int, val taxRateBps: Int, val discount: Discount? = null)

    data class PricedLine(
        val grossCents: Int,
        val taxRateBps: Int,
        val lineDiscountCents: Int,
        /** This line's part of the order discount. */
        val orderShareCents: Int,
    ) {
        val discountCents: Int get() = lineDiscountCents + orderShareCents
        val netCents: Int get() = grossCents - discountCents
    }

    data class Result(
        val lines: List<PricedLine>,
        val orderDiscountCents: Int,
    ) {
        val grossCents: Int get() = lines.sumOf { it.grossCents }
        val lineDiscountCents: Int get() = lines.sumOf { it.lineDiscountCents }
        val discountCents: Int get() = lines.sumOf { it.discountCents }
        /** Before tax, after every discount: also what tip suggestions are based on. */
        val netCents: Int get() = lines.sumOf { it.netCents }
        val taxCents: Int get() = SalesTax.cents(lines.map { it.netCents to it.taxRateBps })
        val totalCents: Int get() = netCents + taxCents
    }

    fun price(lines: List<Line>, orderDiscount: Discount? = null): Result {
        val afterLine = lines.map { it.grossCents - (it.discount?.offCents(it.grossCents) ?: 0) }
        val base = afterLine.sum()
        val orderOff = orderDiscount?.offCents(base) ?: 0
        val shares = allocate(orderOff, afterLine)
        return Result(
            lines = lines.mapIndexed { i, l ->
                PricedLine(l.grossCents, l.taxRateBps, l.grossCents - afterLine[i], shares[i])
            },
            orderDiscountCents = orderOff,
        )
    }

    /**
     * Splits [total] over [weights] in proportion, whole cents, summing exactly to
     * [total]: each gets the floor of its share, and the cents left go one each to
     * the largest remainders (ties to the earlier line).
     */
    internal fun allocate(total: Int, weights: List<Int>): List<Int> {
        val sum = weights.sumOf { it.toLong() }
        if (total == 0 || sum == 0L) return weights.map { 0 }
        val exact = weights.map { it.toLong() * total }
        val floors = exact.map { (it / sum).toInt() }.toMutableList()
        var left = total - floors.sum()
        exact.indices.sortedWith(compareByDescending<Int> { exact[it] % sum }.thenBy { it }).forEach { i ->
            if (left > 0) { floors[i]++; left-- }
        }
        return floors
    }
}

private fun roundHalfUp(numerator: Long, denominator: Long): Int = ((numerator + denominator / 2) / denominator).toInt()
