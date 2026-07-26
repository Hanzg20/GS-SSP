package com.goldsky.carwash.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateInterpolator

/**
 * A custom view that simulates a mechanical split-flap (flip) counter digit.
 * Animates number changes using 3D X-axis rotation.
 */
class FlipDigitView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentValue = 0
    private var nextValue = 0
    private var animationProgress = 0f // 0f to 1f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val camera = Camera()
    private val matrix3d = Matrix()
    private val shadowPaint = Paint().apply {
        color = Color.BLACK
    }

    fun setValue(value: Int, animated: Boolean = true) {
        if (value == currentValue) return
        
        if (!animated) {
            currentValue = value
            nextValue = value
            invalidate()
            return
        }

        nextValue = value
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                if (animationProgress >= 1f) {
                    currentValue = nextValue
                    animationProgress = 0f
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // Responsive text sizing: ensure it fits both width and half-height
        val maxTextSize = height * 0.75f
        textPaint.textSize = maxTextSize
        val textWidth = textPaint.measureText("0")
        if (textWidth > width * 0.8f) {
            textPaint.textSize = maxTextSize * (width * 0.8f / textWidth)
        }

        val centerX = width / 2f
        val centerY = height / 2f
        val textBaseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2

        // 1. Draw static background
        // Top half: Next Value
        drawHalf(canvas, nextValue.toString(), isTop = true, textBaseline)
        // Bottom half: Current Value
        drawHalf(canvas, currentValue.toString(), isTop = false, textBaseline)

        // 2. Draw flipping flap
        canvas.save()
        camera.save()
        
        // Tilt the flap around X axis
        val rotateAngle = -180f * animationProgress
        camera.rotateX(rotateAngle)
        camera.getMatrix(matrix3d)
        
        // Center the rotation
        matrix3d.preTranslate(-centerX, -centerY)
        matrix3d.postTranslate(centerX, centerY)
        canvas.concat(matrix3d)

        if (animationProgress < 0.5f) {
            // First half of flip: show Top of Current Value
            drawHalf(canvas, currentValue.toString(), isTop = true, textBaseline)
            
            // Add darkening shadow as it tilts
            shadowPaint.alpha = (animationProgress * 2 * 180).toInt().coerceIn(0, 180)
            canvas.drawRect(0f, 0f, width.toFloat(), centerY, shadowPaint)
        } else {
            // Second half of flip: show Bottom of Next Value (flipped)
            matrix3d.reset()
            matrix3d.postRotate(180f, centerX, centerY)
            canvas.concat(matrix3d)
            drawHalf(canvas, nextValue.toString(), isTop = false, textBaseline)
            
            // Add lightening shadow as it reveals
            shadowPaint.alpha = ((1f - animationProgress) * 2 * 180).toInt().coerceIn(0, 180)
            canvas.drawRect(0f, centerY, width.toFloat(), height.toFloat(), shadowPaint)
        }

        camera.restore()
        canvas.restore()
        
        // 3. Draw middle separator line
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, borderPaint)
    }

    private fun drawHalf(canvas: Canvas, text: String, isTop: Boolean, baseline: Float) {
        canvas.save()
        if (isTop) {
            canvas.clipRect(0f, 0f, width.toFloat(), height / 2f)
        } else {
            canvas.clipRect(0f, height / 2f, width.toFloat(), height.toFloat())
        }
        
        // Draw card background
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f, cardPaint)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f, borderPaint)
        
        // Draw text
        canvas.drawText(text, width / 2f, baseline, textPaint)
        canvas.restore()
    }
}
