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
    /** Merchant logo for the centre of the QR code (PASS); the QR must use high error correction. */
    var logo: Bitmap? = null
        set(value) { field = value; invalidate() }
    /** PASS: small line under the card, e.g. "Scan not working? Text ..." -- part of the customer's photo. */
    var helpText: String = ""
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
        val ratio = if (mode == Mode.FACE) 1f / 1.586f else 1.08f
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

        if (mode == Mode.FACE) {
            drawFace(canvas, w, h)
        } else {
            val save = canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(card, r, r, android.graphics.Path.Direction.CW) })
            drawPass(canvas, w, h)
            canvas.restoreToCount(save)
        }
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

    /**
     * The customer's own card, made to be photographed: brand + VIP on top,
     * a pure white square holding only the QR code (black on white is what a
     * phone camera and a later scan read best), and below it the member code
     * in large white type beside the balance.
     */
    private fun drawPass(canvas: Canvas, w: Float, h: Float) {
        val pad = w * 0.05f
        drawFinish(canvas, w, h)

        // Header: merchant name left, gold VIP right.
        text.shader = null
        text.typeface = bold
        text.color = Color.argb(230, 255, 255, 255)
        text.letterSpacing = 0.08f
        val headerBase = pad + w * 0.045f
        val name = brandName.uppercase()
        text.textSize = w * 0.045f
        while (text.measureText(name) > w * 0.62f && text.textSize > w * 0.032f) text.textSize *= 0.92f
        canvas.drawText(fit(name, w * 0.62f), pad, headerBase, text)
        text.textSize = w * 0.07f
        text.letterSpacing = 0.04f
        val vipW = text.measureText("VIP")
        text.shader = LinearGradient(0f, headerBase - text.textSize, 0f, headerBase, gold1, gold3, Shader.TileMode.CLAMP)
        canvas.drawText("VIP", w - pad - vipW, headerBase + w * 0.012f, text)
        text.shader = null

        // Bottom row (two columns): MEMBER CODE + the code, BALANCE + amount;
        // the help line (support phone) under it when there is one.
        val helpSize = w * 0.032f
        val bottomBase = h - pad * 0.9f - (if (helpText.isNotBlank()) helpSize * 1.7f else 0f)
        val codeSize = w * 0.085f
        val labelSize = w * 0.03f
        val labelBase = bottomBase - codeSize - w * 0.012f

        // White square: only the QR code, as large as the space allows.
        val panelTop = headerBase + h * 0.03f
        val side = minOf(w - 2 * pad, labelBase - labelSize - h * 0.03f - panelTop)
        val panel = RectF((w - side) / 2, panelTop, (w + side) / 2, panelTop + side)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(panel, w * 0.03f, w * 0.03f, paint)
        // Customers photograph this screen and show the photo to the scanner.
        // Simulated against that (blur, tilt, glare, moire, JPEG; 2026-10-07):
        // logo 24% + thin margin decoded 86.5%; logo 18% + ~4-module quiet
        // zone 96.8% (no logo 99.2%).
        val quiet = side * 0.06f
        qrBitmap?.let {
            // Nearest-neighbour: keeps every module edge sharp when scaled.
            val crisp = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
            canvas.drawBitmap(it, null, RectF(panel.left + quiet, panel.top + quiet, panel.right - quiet, panel.bottom - quiet), crisp)
        }
        logo?.let { drawCenterLogo(canvas, it, panel, side) }

        text.typeface = bold
        text.letterSpacing = 0.2f
        text.textSize = labelSize
        text.color = Color.argb(170, 255, 255, 255)
        canvas.drawText("MEMBER CODE", pad, labelBase, text)
        val balanceLabelW = text.measureText("BALANCE")
        canvas.drawText("BALANCE", w - pad - balanceLabelW, labelBase, text)

        text.typeface = mono
        text.color = Color.WHITE
        text.textSize = codeSize
        text.letterSpacing = 0.18f
        canvas.drawText(memberCode, pad, bottomBase, text)

        text.typeface = bold
        text.letterSpacing = 0f
        text.color = gold2
        text.textSize = codeSize * 0.92f
        canvas.drawText(balanceText, w - pad - text.measureText(balanceText), bottomBase, text)

        if (helpText.isNotBlank()) {
            text.typeface = bold
            text.letterSpacing = 0f
            text.color = Color.argb(200, 255, 255, 255)
            text.textSize = helpSize
            while (text.measureText(helpText) > w - 2 * pad && text.textSize > w * 0.022f) text.textSize *= 0.94f
            canvas.drawText(helpText, (w - text.measureText(helpText)) / 2, h - pad * 0.9f, text)
        }
    }

    /**
     * Merchant logo on a white rounded tile in the middle of the QR code --
     * 18% of its width: 24% failed too often on photos of the screen.
     */
    private fun drawCenterLogo(canvas: Canvas, bmp: Bitmap, panel: RectF, side: Float) {
        val tile = side * 0.18f
        val cx = panel.centerX()
        val cy = panel.centerY()
        val box = RectF(cx - tile / 2, cy - tile / 2, cx + tile / 2, cy + tile / 2)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawRoundRect(box, tile * 0.18f, tile * 0.18f, paint)
        // Fit the logo inside the tile, keeping its aspect ratio.
        val inner = tile * 0.82f
        val scale = minOf(inner / bmp.width, inner / bmp.height)
        val lw = bmp.width * scale
        val lh = bmp.height * scale
        val dst = RectF(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2)
        val save = canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(dst, tile * 0.12f, tile * 0.12f, android.graphics.Path.Direction.CW) })
        canvas.drawBitmap(bmp, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restoreToCount(save)
    }

    /** Brushed-metal hairlines and a glossy top highlight, for a real-card look. */
    private fun drawFinish(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.6f * density
        paint.shader = null
        paint.color = Color.argb(10, 255, 255, 255)
        var x = -h
        while (x < w) {
            canvas.drawLine(x, h, x + h, 0f, paint)
            x += 3.5f * density
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.45f, Color.argb(34, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(card, minOf(w, h) * 0.07f, minOf(w, h) * 0.07f, paint)
        paint.shader = null
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
