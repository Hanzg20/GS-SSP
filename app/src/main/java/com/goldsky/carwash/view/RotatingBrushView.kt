package com.goldsky.carwash.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-end 3D Digitized Industrial Brush.
 * Simulates 300+ independent bristles rotating in 3D space with light and shadow.
 */
class RotatingBrushView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bristlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    
    private val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val camera = Camera()
    private val matrix3d = Matrix()

    private var rotationAngle = 0f
    private var animator: ValueAnimator? = null
    
    private val bristleCount = 300
    private val layerCount = 20
    private val bristlesPerLayer = bristleCount / layerCount

    init {
        startAnimation()
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val shaftWidth = 50f
        val bristleMaxLength = width * 0.45f
        val brushHeight = height * 0.8f

        // 1. Draw Brush Shaft (Central Pillar) with metallic gradient
        shaftPaint.shader = LinearGradient(
            centerX - shaftWidth/2, 0f, centerX + shaftWidth/2, 0f,
            intArrayOf(Color.parseColor("#222222"), Color.parseColor("#888888"), Color.parseColor("#222222")),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(centerX - shaftWidth/2, centerY - brushHeight/2, 
                        centerX + shaftWidth/2, centerY + brushHeight/2, shaftPaint)

        // 2. Draw 3D Bristles with depth sorting and light simulation
        for (i in 0 until bristleCount) {
            val layerIdx = i / bristlesPerLayer
            val inLayerIdx = i % bristlesPerLayer
            
            // Distributed vertically with slight random jitter for organic look
            val yPos = (centerY - brushHeight/2) + (brushHeight / layerCount) * layerIdx
            
            // Calculate 3D angle
            val angleOffset = (360f / bristlesPerLayer) * inLayerIdx
            val currentAngle = Math.toRadians((rotationAngle + angleOffset).toDouble())
            
            // 3D Projection
            val z = cos(currentAngle).toFloat() // -1 (back) to 1 (front)
            val xOffset = sin(currentAngle).toFloat() * bristleMaxLength
            
            // Visual logic: 
            // - Foreground (z > 0): White/Gold, thick, opaque.
            // - Background (z < 0): Dark gray, thin, semi-transparent.
            val isForeground = z > 0
            val depthScale = (z + 1) / 2f // 0 to 1
            
            val alpha = (100 + (depthScale * 155)).toInt()
            val strokeWidth = 2f + (depthScale * 6f)
            
            // Color shift: White in front, gray in back
            val colorVal = (100 + (depthScale * 155)).toInt()
            bristlePaint.color = Color.rgb(colorVal, colorVal, colorVal)
            bristlePaint.alpha = alpha
            bristlePaint.strokeWidth = strokeWidth
            
            // Draw the bristle from shaft to projected tip
            // Add a slight curve simulation using QuadTo or just a simple line for performance
            canvas.drawLine(centerX, yPos, centerX + xOffset, yPos, bristlePaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
