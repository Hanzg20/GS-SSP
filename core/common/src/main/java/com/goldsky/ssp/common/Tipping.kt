package com.goldsky.ssp.common

/**
 * Tip options the customer sees (design: https://claude.ai/artifact/B4cXVxmX1HRKfHdRcxo34q,
 * "Tip"), shared by Ourea Eye, Ourea and Iris so every screen offers the same thing.
 *
 * 10% / 12% / 15% (owner's choice, 2026-10-14; within the 15% cap Quebec's consumer
 * groups ask for), then Custom and No Tip, all alike (CustomerTipPanel). Percentages of the amount BEFORE tax (after discounts), the dollar
 * amount shown under each, whatever the order size (the flat $1/$2/$3 for small
 * orders is gone with it). Nothing is preselected.
 */
object Tipping {
    val PERCENTS = listOf(10, 12, 15)

    /**
     * The bill the customer checks before the tip. Dine-in: ordering happened at the
     * table, rounds went to the kitchen, the customer never watched it being rung up --
     * the payment is the first time they see it. (A counter sale doesn't need one: the
     * customer watched the cart being built on the display.) [title]: "Table T4 · #12".
     */
    data class Review(val title: String, val lines: List<ReviewLine>, val subtotalCents: Int, val taxCents: Int, val totalCents: Int)
    data class ReviewLine(val name: String, val quantity: Int, val amountCents: Int)

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
