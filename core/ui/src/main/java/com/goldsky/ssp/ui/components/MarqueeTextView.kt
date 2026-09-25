package com.goldsky.ssp.ui.components

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Single-line ticker that always scrolls right-to-left, however short the
 * text. TextView's own ellipsize="marquee" only moves when the text overflows
 * the view (and only while selected/focused), so a merchant greeting that fits
 * the 480px screen just sat still.
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val speedPxPerSec = 70f * resources.displayMetrics.density
    private var offset = 0f
    private var lastFrameMs = 0L

    init {
        maxLines = 1
        setHorizontallyScrolling(true)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        offset = 0f
        lastFrameMs = 0L
    }

    override fun onDraw(canvas: Canvas) {
        val str = text?.toString().orEmpty()
        if (str.isEmpty() || width == 0) return

        val now = SystemClock.uptimeMillis()
        if (lastFrameMs != 0L) offset += speedPxPerSec * (now - lastFrameMs) / 1000f
        lastFrameMs = now

        val textWidth = paint.measureText(str)
        val span = width + textWidth
        if (offset > span) offset %= span

        paint.color = currentTextColor
        canvas.drawText(str, width - offset, baseline.toFloat(), paint)
        // Only keeps ticking while the view is actually being drawn (visible).
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lastFrameMs = 0L
    }
}
