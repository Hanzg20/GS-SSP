package com.goldsky.ssp.feature.timer

import com.goldsky.ssp.model.Product
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerPackageTest {

    private fun product(id: String, price: Int, vertical: String = "TIMER", duration: Any? = null, active: Boolean = true) =
        Product(
            id = id, name = "Vacuum", price_cents = price, vertical_type = vertical, is_active = active,
            attributes = buildJsonObject {
                when (duration) {
                    is Int -> put("duration_sec", JsonPrimitive(duration))
                    is String -> put("duration_sec", JsonPrimitive(duration))
                }
            },
        )

    @Test
    fun keepsOnlyActiveTimerProductsWithDuration_sortedByPrice() {
        val packages = TimerPackage.fromProducts(
            listOf(
                product("b", 300, duration = 300),
                product("wash", 400, vertical = "WASH", duration = 240), // same org, other vertical
                product("a", 200, duration = 240),
                product("noDuration", 100),
                product("badDuration", 100, duration = "four minutes"),
                product("zero", 100, duration = 0),
                product("inactive", 100, duration = 60, active = false),
                product("free", 0, duration = 60),
            )
        )
        assertEquals(listOf("a", "b"), packages.map { it.productId })
        assertEquals(240_000L, packages[0].durationMs)
    }

    @Test
    fun verticalMatchIsCaseInsensitive() {
        assertEquals(1, TimerPackage.fromProducts(listOf(product("x", 200, vertical = "timer", duration = 240))).size)
    }

    @Test
    fun formatting() {
        assertEquals("$2", formatPrice(200))
        assertEquals("$2.50", formatPrice(250))
        assertEquals("4 min", formatDuration(240))
        assertEquals("1:30 min", formatDuration(90))
        assertEquals("4:00", formatClock(240_000))
        assertEquals("0:01", formatClock(1)) // never show 0:00 while time remains
        assertEquals("0:00", formatClock(0))
    }
}

class DeclineReasonTest {
    @Test
    fun classifiesProviderMessagesSeenOnTheQ3mini() {
        assertEquals(DeclineReason.CANCELLED, DeclineReason.classify("Payment Error: cancelled by user (-139)", false))
        assertEquals(DeclineReason.UNAVAILABLE, DeclineReason.classify("Communication Timeout (P3)", true))
        assertEquals(DeclineReason.DECLINED, DeclineReason.classify("Payment Error: Do Not Honor (05)", false))
        // A hardware fault wins even if the text looks like a cancel.
        assertEquals(DeclineReason.UNAVAILABLE, DeclineReason.classify("cancelled by user (-139)", true))
    }
}
