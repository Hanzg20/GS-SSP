package com.goldsky.carwash.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.goldsky.carwash.R

/**
 * Custom view that draws a dynamic water wave effect.
 * The water level (progress) can be updated to reflect wash progress.
 */
class WaterWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wavePath = Path()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frontWaveColor = Color.parseColor("#4400D1FF") // Transparent Royal Blue
    private val backWaveColor = Color.parseColor("#2200D1FF")  // Deeper Translucent Blue

    private var waveOffset = 0f
    private val waveHeight = 40f // Increased Amplitude for power feel
    private val waveWidth = 800f // Wavelength
    private var progress = 0f    // 0f to 1.0f

    private var animator: ValueAnimator? = null

    init {
        wavePaint.style = Paint.Style.FILL
        startAnimation()
    }

    /**
     * Sets the water level progress (0.0 to 1.0).
     */
    fun setProgress(value: Float) {
        this.progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                waveOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw Back Wave (offset slightly for depth)
        wavePaint.color = backWaveColor
        wavePaint.alpha = 150
        drawWave(canvas, waveOffset + 0.5f)

        // Draw Front Wave
        wavePaint.color = frontWaveColor
        wavePaint.alpha = 230
        drawWave(canvas, waveOffset)
    }

    private fun drawWave(canvas: Canvas, offset: Float) {
        wavePath.reset()
        val normalizedOffset = offset % 1f
        val startX = -waveWidth + (normalizedOffset * waveWidth)
        
        // Calculate vertical position based on progress (water rises)
        val waterLevelY = height - (progress * height)
        
        wavePath.moveTo(startX, waterLevelY)

        var i = -waveWidth
        while (i < width + waveWidth) {
            wavePath.rQuadTo(waveWidth / 4, -waveHeight, waveWidth / 2, 0f)
            wavePath.rQuadTo(waveWidth / 4, waveHeight, waveWidth / 2, 0f)
            i += waveWidth
        }

        wavePath.lineTo(width.toFloat(), height.toFloat())
        wavePath.lineTo(0f, height.toFloat())
        wavePath.close()

        canvas.drawPath(wavePath, wavePaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
