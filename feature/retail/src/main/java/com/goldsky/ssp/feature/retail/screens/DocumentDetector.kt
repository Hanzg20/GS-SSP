package com.goldsky.ssp.feature.retail.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Finds a receipt in a picture the way a document-scanner app frames it ("扫描王"),
 * without OpenCV or Google Play services (the Q2 has none): a receipt is white paper on a
 * darker surface. On a small grey copy of the frame (~160 px wide): Otsu's threshold splits
 * paper from background, the largest bright patch is the paper, and its four extreme points
 * are the corners. Nothing found (white on white, too little contrast, the paper filling the
 * whole frame or barely in it): null -- the photo is then kept as taken.
 */
object DocumentDetector {

    /** Corners in 0..1 of the picture's width / height: top-left, top-right, bottom-right, bottom-left. */
    data class Quad(val pts: FloatArray) {
        fun x(i: Int) = pts[i * 2]
        fun y(i: Int) = pts[i * 2 + 1]
        /** The largest corner move between two quads, in picture fractions. */
        fun distance(o: Quad): Float = (0 until 4).maxOf { i -> hypot(x(i) - o.x(i), y(i) - o.y(i)) }
        /** Moves each corner [k] (0..1) of the way to [o]: smooths the live frame. */
        fun towards(o: Quad, k: Float) = Quad(FloatArray(8) { pts[it] + (o.pts[it] - pts[it]) * k })
        override fun equals(other: Any?) = other is Quad && pts.contentEquals(other.pts)
        override fun hashCode() = pts.contentHashCode()
    }

    /**
     * [gray]: luminance 0..255, row by row, [w] x [h] (small: ~160 x 120).
     */
    fun detect(gray: IntArray, w: Int, h: Int): Quad? {
        if (w < 16 || h < 16) return null
        // Otsu: the threshold that best splits the histogram into two classes.
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++
        val total = gray.size
        var sumAll = 0.0
        for (i in 0..255) sumAll += i.toDouble() * hist[i]
        var sumB = 0.0; var wB = 0; var best = -1.0; var threshold = 128
        for (t in 0..255) {
            wB += hist[t]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        // Too little contrast between paper and surface: no edge to trust.
        var nB = 0; var sB = 0L; var nF = 0; var sF = 0L
        for (v in gray) if (v > threshold) { nF++; sF += v } else { nB++; sB += v }
        if (nF == 0 || nB == 0 || sF / nF - sB / nB < 40) return null

        // Largest bright patch (4-connected).
        val label = IntArray(total)
        val queue = IntArray(total)
        var bestLabel = 0; var bestSize = 0; var next = 0
        for (start in 0 until total) {
            if (label[start] != 0 || gray[start] <= threshold) continue
            next++
            var head = 0; var tail = 0
            queue[tail++] = start; label[start] = next
            while (head < tail) {
                val p = queue[head++]; val x = p % w; val y = p / w
                fun visit(q: Int) { if (label[q] == 0 && gray[q] > threshold) { label[q] = next; queue[tail++] = q } }
                if (x > 0) visit(p - 1); if (x < w - 1) visit(p + 1)
                if (y > 0) visit(p - w); if (y < h - 1) visit(p + w)
            }
            if (tail > bestSize) { bestSize = tail; bestLabel = next }
        }
        // Barely there, or the whole frame (no background seen = no edges).
        if (bestSize < total * 0.06 || bestSize > total * 0.92) return null

        // Corners: the patch's extreme points along the two diagonals.
        var tl = Int.MAX_VALUE; var br = Int.MIN_VALUE; var tr = Int.MIN_VALUE; var bl = Int.MAX_VALUE
        var tlP = 0; var brP = 0; var trP = 0; var blP = 0
        for (p in 0 until total) {
            if (label[p] != bestLabel) continue
            val x = p % w; val y = p / w
            val s = x + y; val d = x - y
            if (s < tl) { tl = s; tlP = p }
            if (s > br) { br = s; brP = p }
            if (d > tr) { tr = d; trP = p }
            if (d < bl) { bl = d; blP = p }
        }
        fun fx(p: Int) = (p % w + 0.5f) / w
        fun fy(p: Int) = (p / w + 0.5f) / h
        val q = Quad(floatArrayOf(fx(tlP), fy(tlP), fx(trP), fy(trP), fx(brP), fy(brP), fx(blP), fy(blP)))
        // A real sheet: the quad covers most of the patch and isn't folded over itself.
        if (area(q) * w * h < bestSize * 0.6f || !convex(q)) return null
        return q.takeIf { sheetLike(it, w, h) }
    }

    /**
     * Checked on the Q2's first real receipts (2026-10-20, wood table): a good frame is a
     * near-rectangle. A corner on the left / right edge means the lit table joined the paper
     * (Walmart); opposite sides of very different length, a corner in a shadow (Sephora,
     * running off the bottom). Running off the top or bottom alone is fine -- long receipts do.
     */
    private fun sheetLike(q: Quad, w: Int, h: Int): Boolean {
        if ((0 until 4).any { q.x(it) < 0.02f || q.x(it) > 0.98f }) return false
        fun px(i: Int) = q.x(i) * w
        fun py(i: Int) = q.y(i) * h
        fun len(a: Int, b: Int) = hypot(px(a) - px(b), py(a) - py(b))
        val top = len(0, 1); val right = len(1, 2); val bottom = len(3, 2); val left = len(0, 3)
        if (bottom <= 0f || right <= 0f) return false
        // All four corners in the picture: a tilted phone makes a real trapezoid, allow it. Running
        // off the top / bottom, the true corners aren't seen: the shape must stay near-rectangular.
        val offEdge = (0 until 4).any { q.y(it) < 0.02f || q.y(it) > 0.98f }
        val ok = if (offEdge) 0.7f..1.43f else 0.5f..2f
        if (top / bottom !in ok || left / right !in ok) return false
        for (i in 0 until 4) {
            val a = (i + 3) % 4; val c = (i + 1) % 4
            val v1x = px(a) - px(i); val v1y = py(a) - py(i); val v2x = px(c) - px(i); val v2y = py(c) - py(i)
            val cos = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y))
            val deg = Math.toDegrees(kotlin.math.acos(cos.coerceIn(-1f, 1f).toDouble()))
            if (deg !in 60.0..120.0) return false
        }
        return true
    }

    private fun area(q: Quad): Float {
        var a = 0f
        for (i in 0 until 4) { val j = (i + 1) % 4; a += q.x(i) * q.y(j) - q.x(j) * q.y(i) }
        return kotlin.math.abs(a) / 2f
    }

    private fun convex(q: Quad): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = i; val b = (i + 1) % 4; val c = (i + 2) % 4
            val cross = (q.x(b) - q.x(a)) * (q.y(c) - q.y(b)) - (q.y(b) - q.y(a)) * (q.x(c) - q.x(b))
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) { if (sign == 0) sign = s else if (s != sign) return false }
        }
        return true
    }

    /** A small grey copy of [bitmap] (~[width] px wide) for [detect]. */
    fun grayOf(bitmap: Bitmap, width: Int = 200): Triple<IntArray, Int, Int> {
        val w = width.coerceAtMost(bitmap.width)
        val h = (bitmap.height * w.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        if (small !== bitmap) small.recycle()
        return Triple(IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 }, w, h)
    }

    /**
     * The receipt cut out of [photo] along [q] and squared up (perspective corrected), as if
     * scanned flat. Its size follows the sheet's own edges in the photo.
     */
    fun flatten(photo: Bitmap, q: Quad): Bitmap {
        val src = FloatArray(8) { if (it % 2 == 0) q.pts[it] * photo.width else q.pts[it] * photo.height }
        fun len(a: Int, b: Int) = hypot(src[a * 2] - src[b * 2], src[a * 2 + 1] - src[b * 2 + 1])
        val outW = ((len(0, 1) + len(3, 2)) / 2).roundToInt().coerceIn(64, 4000)
        val outH = ((len(0, 3) + len(1, 2)) / 2).roundToInt().coerceIn(64, 6000)
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val m = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
