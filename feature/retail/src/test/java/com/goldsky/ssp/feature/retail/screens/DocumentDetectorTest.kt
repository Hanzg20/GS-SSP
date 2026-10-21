package com.goldsky.ssp.feature.retail.screens

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DocumentDetectorTest {
    private val w = 160
    private val h = 120

    /** A frame: [surface] grey with noise, and a [paper] grey quad with corners [c] (pixels: TL, TR, BR, BL). */
    private fun frame(c: List<Pair<Int, Int>>, paper: Int = 225, surface: Int = 90, seed: Int = 1): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { p ->
            val x = p % w + 0.5; val y = p / w + 0.5
            val inside = (0 until 4).all { i ->
                val (ax, ay) = c[i]; val (bx, by) = c[(i + 1) % 4]
                (bx - ax) * (y - ay) - (by - ay) * (x - ax) >= 0
            }
            ((if (inside) paper else surface) + rnd.nextInt(-12, 13)).coerceIn(0, 255)
        }
    }

    private fun assertCorners(c: List<Pair<Int, Int>>, q: DocumentDetector.Quad?) {
        assertNotNull(q)
        for (i in 0 until 4) {
            val dx = q!!.x(i) * w - c[i].first; val dy = q.y(i) * h - c[i].second
            assertTrue("corner $i off by ($dx, $dy)", kotlin.math.abs(dx) <= 3 && kotlin.math.abs(dy) <= 3)
        }
    }

    @Test fun `receipt lying straight`() {
        val c = listOf(60 to 10, 100 to 10, 100 to 110, 60 to 110)
        assertCorners(c, DocumentDetector.detect(frame(c), w, h))
    }

    @Test fun `receipt at an angle`() {
        val c = listOf(70 to 8, 105 to 18, 85 to 112, 50 to 102)
        assertCorners(c, DocumentDetector.detect(frame(c), w, h))
    }

    @Test fun `receipt in perspective (phone tilted)`() {
        val c = listOf(62 to 12, 98 to 12, 112 to 108, 48 to 108)
        assertCorners(c, DocumentDetector.detect(frame(c), w, h))
    }

    @Test fun `white receipt on a white table finds nothing`() {
        val c = listOf(60 to 10, 100 to 10, 100 to 110, 60 to 110)
        assertNull(DocumentDetector.detect(frame(c, paper = 225, surface = 215), w, h))
    }

    @Test fun `paper filling the whole frame or barely in it finds nothing`() {
        assertNull(DocumentDetector.detect(frame(listOf(0 to 0, 160 to 0, 160 to 120, 0 to 120)), w, h))
        assertNull(DocumentDetector.detect(frame(listOf(78 to 58, 84 to 58, 84 to 64, 78 to 64)), w, h))
    }

    @Test fun `long receipt running off the top and bottom is still framed`() {
        val c = listOf(60 to 0, 100 to 0, 100 to 120, 60 to 120)
        assertNotNull(DocumentDetector.detect(frame(c), w, h))
    }

    @Test fun `table joined to the paper at the side is not trusted`() {
        // Walmart, 2026-10-20: lit wood on the left made one patch with the receipt.
        val c = listOf(0 to 30, 110 to 12, 118 to 120, 36 to 120)
        assertNull(DocumentDetector.detect(frame(c), w, h))
    }

    @Test fun `a strong trapezoid (a corner lost in shadow) is not trusted`() {
        // Sephora, 2026-10-20: bottom half the width of the top.
        val c = listOf(40 to 0, 112 to 0, 80 to 119, 42 to 119)
        assertNull(DocumentDetector.detect(frame(c), w, h))
    }

    @Test fun `smoothing and distance`() {
        val a = DocumentDetector.Quad(FloatArray(8) { 0f })
        val b = DocumentDetector.Quad(FloatArray(8) { 1f })
        val m = a.towards(b, 0.5f)
        assertTrue(m.pts.all { it == 0.5f })
        assertTrue(a.distance(b) > 1.4f)
    }
}
