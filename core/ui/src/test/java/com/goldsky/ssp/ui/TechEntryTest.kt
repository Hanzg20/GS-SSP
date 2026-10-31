package com.goldsky.ssp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TechEntryTest {
    private val w = 480
    private val h = 480
    private val tl = 10f to 10f
    private val tr = 470f to 10f
    private val br = 470f to 470f
    private val bl = 10f to 470f
    private val middle = 240f to 240f

    private fun CornerSequence.tap(p: Pair<Float, Float>, at: Long) = onDown(p.first, p.second, w, h, at)

    @Test fun fourCornersInOrderOpens() {
        val s = CornerSequence()
        assertFalse(s.tap(tl, 0)); assertFalse(s.tap(tr, 500)); assertFalse(s.tap(br, 1000))
        assertTrue(s.tap(bl, 1500))
    }

    @Test fun wrongOrderDoesNot() {
        val s = CornerSequence()
        s.tap(tl, 0); s.tap(br, 500); s.tap(tr, 1000)
        assertFalse(s.tap(bl, 1500))
    }

    @Test fun tapInTheMiddleResets() {
        val s = CornerSequence()
        s.tap(tl, 0); s.tap(tr, 500); s.tap(middle, 700); s.tap(br, 1000)
        assertFalse(s.tap(bl, 1500))
    }

    @Test fun tooSlowDoesNot() {
        val s = CornerSequence()
        s.tap(tl, 0); s.tap(tr, 2000); s.tap(br, 4000)
        assertFalse(s.tap(bl, 6000))
    }

    @Test fun topLeftRestartsTheSequence() {
        val s = CornerSequence()
        s.tap(tl, 0); s.tap(tr, 500); s.tap(tl, 10_000)
        s.tap(tr, 10_500); s.tap(br, 11_000)
        assertTrue(s.tap(bl, 11_500))
    }

    @Test fun repeatedTapsOnOneCornerDoNot() {
        val s = CornerSequence()
        repeat(8) { assertFalse(s.tap(bl, it * 100L)) }
    }

    @Test fun pinMustBeSixToEightDigits() {
        assertFalse(TechPin.accepts("1234", "1234"))
        assertFalse(TechPin.accepts("", null))
        assertFalse(TechPin.accepts("12345a", "12345a"))
        assertFalse(TechPin.accepts("123456789", "123456789"))
        assertFalse(TechPin.accepts("654321", "123456"))
        assertTrue(TechPin.accepts("123456", "123456"))
        assertTrue(TechPin.accepts("12345678", "12345678"))
    }
}
