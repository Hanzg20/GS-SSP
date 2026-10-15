package com.goldsky.ssp.common

/**
 * Tip options the customer sees (design: https://claude.ai/artifact/B4cXVxmX1HRKfHdRcxo34q,
 * "Tip"), shared by Ourea Eye, Ourea and Iris so every screen offers the same thing.
 *
 * The layout the owner chose (2026-10-14, "the version Canadians like best"):
 * 10% / 12% / 15% / Custom in one row, "No tip" under them, a thank-you line for
 * local staff. Percentages of the amount BEFORE tax (after discounts), the dollar
 * amount shown under each, whatever the order size (the flat $1/$2/$3 for small
 * orders is gone with it). Nothing is preselected.
 */
object Tipping {
    val PERCENTS = listOf(10, 12, 15)

    /** [percent] null: a flat amount (none offered now; kept for the screens that draw both). */
    data class Option(val tipCents: Int, val percent: Int?)

    fun options(basisCents: Int): List<Option> =
        PERCENTS.map { Option(percentOf(basisCents, it * 100), it) }

    /** [bps] of [basisCents], half a cent rounding up. */
    fun percentOf(basisCents: Int, bps: Int): Int = ((basisCents.toLong() * bps + 5_000) / 10_000).toInt()

    /**
     * The most a typed-in tip may be: the bigger of the amount before tax and $20,
     * so a slip of the finger ("5000" for "50.00"... or an extra zero) can't turn a
     * $12 lunch into a $120 tip. Percent tips stop at 100%.
     */
    fun maxCustomCents(basisCents: Int): Int = maxOf(basisCents, 2_000)
    const val MAX_CUSTOM_PERCENT = 100
}
