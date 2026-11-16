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

    private fun promo(id: String, price: Int, credit: Int) = Product(
        id = id, name = id, price_cents = price, vertical_type = "WASH",
        attributes = kotlinx.serialization.json.buildJsonObject { put("credit_cents", kotlinx.serialization.json.JsonPrimitive(credit)) },
    )

    // Eagleson 2026-11-16: five regular packages + buy 4 get 1 on a 5-button screen.
    @Test
    fun promotionAlwaysGetsAButton() {
        val list = mixed.forVertical("WASH") + promo("b4g1", 1600, 2000)
        assertEquals(listOf("starter", "basic", "deluxe", "extra", "b4g1"), list.forButtons(5).map { it.id })
    }

    @Test
    fun fitsAsIs() {
        val list = listOf(p("starter", 400, "WASH"), promo("b4g1", 1600, 2000))
        assertEquals(list, list.forButtons(5))
    }

    @Test
    fun oneRegularPackageStays() {
        val list = listOf(p("starter", 400, "WASH")) + (1..6).map { promo("p$it", 1000, 1200) }
        assertEquals(listOf("starter", "p1", "p2", "p3", "p4"), list.forButtons(5).map { it.id })
    }

    @Test
    fun creditNotAboveThePriceIsNoPromotion() {
        assertEquals(false, promo("x", 500, 500).isPromotion)
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertEquals(1, listOf(p("x", 400, "wash")).forVertical("WASH").size)
    }
}
