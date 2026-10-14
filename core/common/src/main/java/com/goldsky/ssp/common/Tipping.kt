package com.goldsky.ssp.common

/**
 * Tip options the customer sees (design: https://claude.ai/artifact/B4cXVxmX1HRKfHdRcxo34q,
 * "Tip"), shared by Ourea Eye, Ourea and Iris so every screen offers the same thing.
 *
 * Canadian practice: percentages of the amount BEFORE tax (after discounts), the
 * dollar amount shown under each; small orders (under $10 before tax) get flat
 * $1 / $2 / $3 instead, where 15% would be a few cents. "No tip" and "Custom" are
 * as big as the rest and nothing is preselected.
 */
object Tipping {
    val PERCENTS = listOf(15, 18, 20)
    val SMALL_ORDER_AMOUNTS = listOf(100, 200, 300)
    /** Below this (before tax) the options are flat dollars. */
    const val SMALL_ORDER_CENTS = 1000

    /** [percent] null: a flat amount. */
    data class Option(val tipCents: Int, val percent: Int?)

    fun options(basisCents: Int): List<Option> =
        if (basisCents < SMALL_ORDER_CENTS) SMALL_ORDER_AMOUNTS.map { Option(it, null) }
        else PERCENTS.map { Option(percentOf(basisCents, it * 100), it) }

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
