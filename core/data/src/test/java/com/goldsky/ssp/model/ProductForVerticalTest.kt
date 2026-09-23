package com.goldsky.ssp.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductForVerticalTest {

    private fun p(id: String, price: Int, vertical: String) = Product(id = id, name = id, price_cents = price, vertical_type = vertical)

    // Shape of a real org config once Timer products are published: sorted by
    // price, so the vacuum packages come first and would have taken wash's
    // first two buttons (and pushed Extra/Premium off the 5-button screen).
    private val mixed = listOf(
        p("vac2", 200, "TIMER"), p("vac3", 300, "TIMER"),
        p("starter", 400, "WASH"), p("basic", 500, "WASH"), p("deluxe", 600, "WASH"),
        p("extra", 700, "WASH"), p("premium", 800, "WASH"),
    )

    @Test
    fun washSeesOnlyWashPackages_inOrder() {
        assertEquals(listOf("starter", "basic", "deluxe", "extra", "premium"), mixed.forVertical("WASH").map { it.id })
    }

    @Test
    fun timerSeesOnlyTimerPackages() {
        assertEquals(listOf("vac2", "vac3"), mixed.forVertical("TIMER").map { it.id })
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertEquals(1, listOf(p("x", 400, "wash")).forVertical("WASH").size)
    }
}
