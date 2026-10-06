package com.goldsky.ssp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * The VIP membership card -- an electronic pass, not a plastic card (there
 * is none: the member code IS the card). Drawn as a premium digital wallet
 * pass: black with a brushed-gold sheen, merchant name, a "DIGITAL" tag, a
 * QR mark where a plastic card would carry its chip, and a gold "VIP".
 *
 * [Mode.FACE]: the pass face (VIP page hero).
 * [Mode.PASS]: the customer's own pass with the member QR code, the
 * 6-character code and the balance (shown after buying a card).
 */
class VipCardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { FACE, PASS }

    var mode = Mode.FACE
        set(value) { field = value; requestLayout(); invalidate() }
    var brandName: String = ""
        set(value) { field = value; invalidate() }
    var qrBitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    var memberCode: String = ""
        set(value) { field = value; invalidate() }
    var balanceText: String = ""
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val card = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val gold1 = Color.parseColor("#FFE29A")
    private val gold2 = Color.parseColor("#FFB800")
    private val gold3 = Color.parseColor("#B8860B")

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // ISO/IEC 7810 ID-1 ratio for the face; a taller pass for the QR.
        val ratio = if (mode == Mode.FACE) 1f / 1.586f else 1.12f
        val wanted = (w * ratio).toInt()
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted, MeasureSpec.getSize(heightMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = 2 * density
        card.set(inset, inset, w - inset, h - inset)
        val r = minOf(w, h) * 0.07f

        // Body: near-black, lifted at the top-left.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#2A2A30"), Color.parseColor("#09090B"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(card, r, r, paint)

        // Brushed-gold sheen across the diagonal.
        paint.shader = LinearGradient(
            w * 0.15f, 0f, w * 0.85f, h,
            intArrayOf(Color.TRANSPARENT, Color.argb(46, 255, 214, 120), Color.TRANSPARENT),
            floatArrayOf(0.3f, 0.5f, 0.7f), Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(card, r, r, paint)

        // Gold hairline edge.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(gold1, gold3, gold2), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(card, r, r, paint)
        paint.style = Paint.Style.FILL
        paint.shader = null

        if (mode == Mode.FACE) drawFace(canvas, w, h) else drawPass(canvas, w, h)
    }

    private fun drawFace(canvas: Canvas, w: Float, h: Float) {
        val pad = w * 0.07f
        // Merchant name, top left.
        text.shader = null
        text.color = Color.argb(225, 255, 255, 255)
        text.typeface = bold
        text.letterSpacing = 0.08f
        val nameMax = w * 0.58f
        val name = brandName.uppercase()
        // Shrink a long merchant name before resorting to an ellipsis.
        text.textSize = h * 0.095f
        while (text.measureText(name) > nameMax && text.textSize > h * 0.06f) text.textSize *= 0.92f
        canvas.drawText(fit(name, nameMax), pad, pad + h * 0.095f, text)

        // "DIGITAL" tag, top right.
        text.textSize = h * 0.06f
        text.letterSpacing = 0.25f
        val tag = "DIGITAL"
        val tagW = text.measureText(tag)
        val tagRect = RectF(w - pad - tagW - h * 0.07f, pad * 0.75f, w - pad, pad * 0.75f + h * 0.11f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = Color.argb(170, 255, 214, 120)
        canvas.drawRoundRect(tagRect, tagRect.height() / 2, tagRect.height() / 2, paint)
        paint.style = Paint.Style.FILL
        text.color = Color.argb(220, 255, 226, 154)
        canvas.drawText(tag, tagRect.centerX() - tagW / 2 + h * 0.008f, tagRect.centerY() + text.textSize * 0.36f, text)

        // QR mark where a plastic card has its chip, with what it's for.
        val qrSize = h * 0.24f
        val qrTop = h * 0.36f
        drawQrMark(canvas, pad, qrTop, qrSize)
        text.color = Color.argb(200, 255, 255, 255)
        text.textSize = h * 0.07f
        text.letterSpacing = 0.12f
        canvas.drawText("SCAN TO WASH", pad + qrSize + w * 0.04f, qrTop + qrSize / 2 + text.textSize * 0.36f, text)

        // "MEMBER" over a large gold "VIP", bottom right.
        text.textSize = h * 0.30f
        text.letterSpacing = 0.04f
        val vipWidth = text.measureText("VIP")
        val vipBase = h - pad
        text.shader = LinearGradient(0f, vipBase - text.textSize, 0f, vipBase, gold1, gold3, Shader.TileMode.CLAMP)
        canvas.drawText("VIP", w - pad - vipWidth, vipBase, text)
        text.shader = null
        text.color = Color.argb(200, 255, 226, 154)
        text.textSize = h * 0.07f
        text.letterSpacing = 0.35f
        val member = "MEMBER"
        canvas.drawText(member, w - pad - text.measureText(member), vipBase - h * 0.28f, text)

        // Bottom left: the promise that matters most.
        text.color = Color.argb(150, 255, 255, 255)
        text.textSize = h * 0.065f
        text.letterSpacing = 0.15f
        canvas.drawText(fit("NO EXPIRY", w * 0.4f), pad, vipBase, text)
    }

    private fun drawPass(canvas: Canvas, w: Float, h: Float) {
        val pad = w * 0.06f
        // Header: merchant name left, gold VIP right.
        text.shader = null
        text.typeface = bold
        text.color = Color.argb(225, 255, 255, 255)
        text.letterSpacing = 0.08f
        val headerBase = pad + w * 0.055f
        val name = brandName.uppercase()
        text.textSize = w * 0.055f
        while (text.measureText(name) > w * 0.6f && text.textSize > w * 0.035f) text.textSize *= 0.92f
        canvas.drawText(fit(name, w * 0.6f), pad, headerBase, text)
        text.textSize = w * 0.085f
        val vipW = text.measureText("VIP")
        text.shader = LinearGradient(0f, headerBase - text.textSize, 0f, headerBase, gold1, gold3, Shader.TileMode.CLAMP)
        canvas.drawText("VIP", w - pad - vipW, headerBase + w * 0.01f, text)
        text.shader = null

        // QR on a white tile.
        val qrSize = minOf(w * 0.56f, h * 0.52f)
        val qrLeft = (w - qrSize) / 2
        val qrTop = headerBase + h * 0.06f
        paint.color = Color.WHITE
        val tile = RectF(qrLeft - qrSize * 0.06f, qrTop - qrSize * 0.06f, qrLeft + qrSize * 1.06f, qrTop + qrSize * 1.06f)
        canvas.drawRoundRect(tile, qrSize * 0.06f, qrSize * 0.06f, paint)
        qrBitmap?.let { canvas.drawBitmap(it, null, RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize), paint) }

        // Member code, spaced like a card number.
        text.typeface = mono
        text.color = Color.WHITE
        text.textSize = w * 0.085f
        text.letterSpacing = 0.25f
        val codeBase = tile.bottom + h * 0.11f
        canvas.drawText(memberCode, (w - text.measureText(memberCode)) / 2, codeBase, text)

        // Balance, bottom: label left, amount right in gold.
        text.typeface = bold
        text.letterSpacing = 0.2f
        text.textSize = w * 0.04f
        text.color = Color.argb(160, 255, 255, 255)
        val bottomBase = h - pad
        canvas.drawText("BALANCE", pad, bottomBase, text)
        text.letterSpacing = 0f
        text.textSize = w * 0.075f
        text.color = gold2
        canvas.drawText(balanceText, w - pad - text.measureText(balanceText), bottomBase, text)
    }

    /** A stylised QR mark in gold: three finder squares and a few modules. */
    private fun drawQrMark(canvas: Canvas, x: Float, y: Float, size: Float) {
        val m = size / 7f
        paint.shader = LinearGradient(x, y, x + size, y + size, gold1, gold3, Shader.TileMode.CLAMP)
        fun finder(fx: Float, fy: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = m * 0.7f
            canvas.drawRoundRect(RectF(fx + m * 0.35f, fy + m * 0.35f, fx + 3 * m - m * 0.35f, fy + 3 * m - m * 0.35f), m * 0.4f, m * 0.4f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(RectF(fx + m, fy + m, fx + 2 * m, fy + 2 * m), m * 0.2f, m * 0.2f, paint)
        }
        finder(x, y)
        finder(x + 4 * m, y)
        finder(x, y + 4 * m)
        for ((cx, cy) in listOf(4 to 4, 5 to 5, 6 to 4, 4 to 6, 6 to 6, 5 to 3)) {
            canvas.drawRoundRect(RectF(x + cx * m + m * 0.1f, y + cy * m + m * 0.1f, x + (cx + 1) * m - m * 0.1f, y + (cy + 1) * m - m * 0.1f), m * 0.2f, m * 0.2f, paint)
        }
        paint.shader = null
    }

    /** Shortens [s] with an ellipsis until it fits [max] px at the current text size. */
    private fun fit(s: String, max: Float): String {
        if (text.measureText(s) <= max) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }
}
