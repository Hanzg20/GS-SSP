package com.goldsky.ssp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * Text ads and announcements drawn as real signs instead of loose text on the
 * ad screen (owner, 2026-10-07: "too casual"):
 *
 * [Style.BILLBOARD] (TEXT_AD): a backlit light box -- dark steel frame ringed
 * with chasing marquee bulbs, a warm amber face, a black "SPECIAL OFFER"
 * plate, bold dark copy.
 *
 * [Style.NOTICE] (TEXT announcement): a wooden-framed cork board with a cream
 * sheet pinned on, slightly tilted, red "NOTICE" heading, dark copy.
 *
 * The copy auto-fits the space (long multi-line ads shrink to fit).
 */
class SignboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Style { BILLBOARD, NOTICE }

    var style = Style.BILLBOARD
        set(value) { field = value; invalidate() }
    var text: String = ""
        set(value) { field = value; cachedLayout = null; invalidate() }

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    // The fitted copy's StaticLayout keeps this paint: never reused for labels.
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private var cachedLayout: StaticLayout? = null
    private var cachedKey = ""

    // Marquee chase: which third of the bulbs is lit.
    private var chase = 0
    private val chaseAnim = ValueAnimator.ofInt(0, 3).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { val v = it.animatedValue as Int; if (v != chase) { chase = v; invalidate() } }
    }

    // Fixed cork speckles (same every frame).
    private val speckles = Random(42).let { r -> List(260) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat()) } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); chaseAnim.start() }
    override fun onDetachedFromWindow() { chaseAnim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) chaseAnim.start() else chaseAnim.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        if (style == Style.BILLBOARD) drawBillboard(canvas) else drawNotice(canvas)
    }

    // ---------------------------------------------------------------- billboard

    private fun drawBillboard(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val m = 10 * d
        val outer = RectF(m, m, w - m, h - m)
        val r = 18 * d

        // Steel frame.
        paint.shader = LinearGradient(0f, outer.top, 0f, outer.bottom,
            intArrayOf(Color.parseColor("#4A4A52"), Color.parseColor("#26262B"), Color.parseColor("#141417")), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(outer, r, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = Color.parseColor("#6B6B75")
        c.drawRoundRect(outer, r, r, paint)
        paint.style = Paint.Style.FILL

        // Bulbs along the frame band.
        val band = 22 * d
        val track = RectF(outer.left + band / 2, outer.top + band / 2, outer.right - band / 2, outer.bottom - band / 2)
        drawBulbs(c, track, 24 * d, 4.2f * d)

        // Backlit face.
        val face = RectF(outer.left + band, outer.top + band, outer.right - band, outer.bottom - band)
        paint.shader = LinearGradient(0f, face.top, 0f, face.bottom,
            intArrayOf(Color.parseColor("#FFD54F"), Color.parseColor("#FFB300"), Color.parseColor("#FF8F00")), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(face, 8 * d, 8 * d, paint)
        // Glow + gloss.
        paint.shader = RadialGradient(face.centerX(), face.top + face.height() * 0.35f, face.width() * 0.7f,
            Color.argb(110, 255, 255, 230), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRoundRect(face, 8 * d, 8 * d, paint)
        paint.shader = null

        // "SPECIAL OFFER" plate.
        textPaint.typeface = bold
        textPaint.textSize = 15 * d
        textPaint.letterSpacing = 0.22f
        val label = "SPECIAL OFFER"
        val lw = textPaint.measureText(label) + 28 * d
        val plate = RectF(face.centerX() - lw / 2, face.top + 14 * d, face.centerX() + lw / 2, face.top + 14 * d + 30 * d)
        paint.color = Color.parseColor("#1A1208")
        c.drawRoundRect(plate, plate.height() / 2, plate.height() / 2, paint)
        textPaint.color = Color.parseColor("#FFD54F")
        c.drawText(label, plate.centerX() - textPaint.measureText(label) / 2, plate.centerY() + textPaint.textSize * 0.36f, textPaint)
        textPaint.letterSpacing = 0f

        // Copy.
        val body = RectF(face.left + 18 * d, plate.bottom + 14 * d, face.right - 18 * d, face.bottom - 18 * d)
        drawFitted(c, body, Color.parseColor("#1A1208"), bold, maxSp = 40f, minSp = 15f)
    }

    private fun drawBulbs(c: Canvas, track: RectF, spacing: Float, radius: Float) {
        val pts = mutableListOf<Pair<Float, Float>>()
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
            val len = kotlin.math.hypot(x1 - x0, y1 - y0)
            val n = (len / spacing).toInt().coerceAtLeast(1)
            for (i in 0 until n) { val t = i / n.toFloat(); pts += (x0 + (x1 - x0) * t) to (y0 + (y1 - y0) * t) }
        }
        edge(track.left, track.top, track.right, track.top)
        edge(track.right, track.top, track.right, track.bottom)
        edge(track.right, track.bottom, track.left, track.bottom)
        edge(track.left, track.bottom, track.left, track.top)
        pts.forEachIndexed { i, (x, y) ->
            val lit = (i + chase) % 3 == 0
            if (lit) {
                paint.shader = RadialGradient(x, y, radius * 3f, Color.argb(150, 255, 214, 79), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawCircle(x, y, radius * 3f, paint)
                paint.shader = null
                paint.color = Color.parseColor("#FFF3C4")
            } else {
                paint.color = Color.parseColor("#7A6330")
            }
            c.drawCircle(x, y, radius, paint)
        }
    }

    // ------------------------------------------------------------------ notice

    private fun drawNotice(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val m = 10 * d
        val frame = RectF(m, m, w - m, h - m)

        // Wooden frame.
        paint.shader = LinearGradient(frame.left, frame.top, frame.right, frame.bottom,
            intArrayOf(Color.parseColor("#8D6E63"), Color.parseColor("#5D4037"), Color.parseColor("#4E342E")), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(frame, 10 * d, 10 * d, paint)
        paint.shader = null

        // Cork.
        val fb = 16 * d
        val cork = RectF(frame.left + fb, frame.top + fb, frame.right - fb, frame.bottom - fb)
        paint.color = Color.parseColor("#C9A06E")
        c.drawRect(cork, paint)
        speckles.forEach { (sx, sy, sz) ->
            paint.color = if (sz > 0.5f) Color.argb(70, 120, 80, 40) else Color.argb(60, 235, 205, 160)
            c.drawCircle(cork.left + sx * cork.width(), cork.top + sy * cork.height(), (0.8f + sz * 1.6f) * d, paint)
        }
        // Inner shadow under the frame.
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d; paint.color = Color.argb(70, 0, 0, 0)
        c.drawRect(cork, paint)
        paint.style = Paint.Style.FILL

        // Pinned paper, slightly tilted.
        val pm = 18 * d
        val paper = RectF(cork.left + pm, cork.top + pm + 6 * d, cork.right - pm, cork.bottom - pm)
        c.save()
        c.rotate(-1.4f, paper.centerX(), paper.centerY())
        paint.color = Color.argb(70, 0, 0, 0)
        c.drawRect(RectF(paper.left + 4 * d, paper.top + 5 * d, paper.right + 4 * d, paper.bottom + 5 * d), paint)
        paint.shader = LinearGradient(0f, paper.top, 0f, paper.bottom, Color.parseColor("#FFFBEE"), Color.parseColor("#F6EDD3"), Shader.TileMode.CLAMP)
        c.drawRect(paper, paint)
        paint.shader = null

        // NOTICE heading + rule.
        textPaint.typeface = bold
        textPaint.textSize = 20 * d
        textPaint.letterSpacing = 0.28f
        textPaint.color = Color.parseColor("#C62828")
        val head = "NOTICE"
        val headBase = paper.top + 40 * d
        c.drawText(head, paper.centerX() - textPaint.measureText(head) / 2, headBase, textPaint)
        textPaint.letterSpacing = 0f
        paint.color = Color.parseColor("#C62828")
        c.drawRect(paper.left + 24 * d, headBase + 10 * d, paper.right - 24 * d, headBase + 12 * d, paint)

        val body = RectF(paper.left + 24 * d, headBase + 24 * d, paper.right - 24 * d, paper.bottom - 22 * d)
        drawFitted(c, body, Color.parseColor("#2B2B2B"), Typeface.create(Typeface.SERIF, Typeface.NORMAL), maxSp = 30f, minSp = 14f)
        c.restore()

        // Push pin (drawn upright, over the paper's top edge).
        val px = paper.centerX(); val py = paper.top + 2 * d
        paint.color = Color.argb(80, 0, 0, 0)
        c.drawCircle(px + 2.5f * d, py + 3 * d, 9 * d, paint)
        paint.shader = RadialGradient(px - 3 * d, py - 3 * d, 11 * d, Color.parseColor("#FF6B6B"), Color.parseColor("#B71C1C"), Shader.TileMode.CLAMP)
        c.drawCircle(px, py, 9 * d, paint)
        paint.shader = null
        paint.color = Color.argb(170, 255, 255, 255)
        c.drawCircle(px - 3 * d, py - 3 * d, 2.4f * d, paint)
    }

    // ------------------------------------------------------------------ copy

    /** Largest size (sp, from [maxSp] down to [minSp]) whose layout fits [box]; centered. */
    private fun drawFitted(c: Canvas, box: RectF, color: Int, face: Typeface, maxSp: Float, minSp: Float) {
        if (text.isBlank() || box.width() <= 0 || box.height() <= 0) return
        val key = "$style|${box.width().toInt()}x${box.height().toInt()}|$text"
        val sp = resources.displayMetrics.scaledDensity
        var layout = cachedLayout.takeIf { key == cachedKey }
        if (layout == null) {
            bodyPaint.typeface = face
            bodyPaint.letterSpacing = 0f
            var size = maxSp
            while (true) {
                bodyPaint.textSize = size * sp
                layout = StaticLayout.Builder.obtain(text.trim(), 0, text.trim().length, bodyPaint, box.width().toInt())
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.12f)
                    .setIncludePad(false)
                    .build()
                if (layout.height <= box.height() || size <= minSp) break
                size -= 1f
            }
            cachedLayout = layout
            cachedKey = key
        }
        val l = layout ?: return
        l.paint.color = color
        c.save()
        c.translate(box.left, box.top + ((box.height() - l.height) / 2f).coerceAtLeast(0f))
        c.clipRect(0f, 0f, box.width(), box.height())
        l.draw(c)
        c.restore()
    }
}
