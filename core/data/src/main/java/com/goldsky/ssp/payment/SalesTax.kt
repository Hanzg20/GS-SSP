package com.goldsky.ssp.payment

import kotlin.math.roundToInt

/**
 * Sales tax in cents. Amounts are summed per rate and each rate's tax is
 * rounded half-up once -- not per line, and not truncated (the old
 * `(subtotal * rate / 100.0).toInt()` dropped fractions of a cent every sale).
 */
object SalesTax {

    /** [lines] = (amountCents, rateBps); rateBps 1300 = 13%. */
    fun cents(lines: List<Pair<Int, Int>>): Int =
        lines.groupBy({ it.second }, { it.first.toLong() })
            .entries.sumOf { (bps, amounts) -> roundHalfUp(amounts.sum() * bps, 10_000L) }

    fun cents(amountCents: Int, rateBps: Int): Int = cents(listOf(amountCents to rateBps))

    /**
     * Per-rate breakdown for the receipt: rateBps -> (taxable amount, tax),
     * non-zero rates only, ascending. Sums to [cents] for the same lines.
     */
    fun byRate(lines: List<Pair<Int, Int>>): List<Triple<Int, Int, Int>> =
        lines.filter { it.second > 0 }
            .groupBy({ it.second }, { it.first })
            .toSortedMap()
            .map { (bps, amounts) -> Triple(bps, amounts.sum(), cents(amounts.sum(), bps)) }

    /** Device-level fallback rate (Settings stores a percent, e.g. 13.0). */
    fun deviceRateBps(): Int = (DeviceRepository.getTaxRate() * 100).roundToInt()

    private fun roundHalfUp(numerator: Long, denominator: Long): Int =
        ((numerator + denominator / 2) / denominator).toInt()
}
