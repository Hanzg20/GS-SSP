package com.goldsky.ssp.feature.wash

import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.ComposeView
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.sin

/**
 * The scene behind a wash package card (same drawn-not-bitmap idea as Aegis Laundry's
 * machines): a car being sprayed by a wand, water drops falling on it and foam bubbles
 * rising. Kept faint so the price on top stays the thing you read. [level] 0..4 (the
 * $4..$8 cards): a little more water the more time the package buys. Draws nothing
 * touchable -- taps go to the card.
 */
@Composable
fun WashCardScene(level: Int) {
    val t = rememberInfiniteTransition(label = "washcard")
    val fall by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "fall")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "bob")
    val cyan = Color(0xFF5BD6F5)
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Car, side view, low along the bottom (squashed and faint: the price, name and
        // minutes above it stay the thing you read).
        val base = h * 0.95f
        val body = Path().apply {
            moveTo(w * 0.10f, base - h * 0.06f)
            lineTo(w * 0.10f, base - h * 0.16f)
            quadraticBezierTo(w * 0.12f, base - h * 0.21f, w * 0.24f, base - h * 0.22f)
            lineTo(w * 0.34f, base - h * 0.33f)
            quadraticBezierTo(w * 0.40f, base - h * 0.37f, w * 0.52f, base - h * 0.37f)
            lineTo(w * 0.64f, base - h * 0.37f)
            quadraticBezierTo(w * 0.70f, base - h * 0.36f, w * 0.76f, base - h * 0.24f)
            quadraticBezierTo(w * 0.90f, base - h * 0.22f, w * 0.90f, base - h * 0.14f)
            lineTo(w * 0.90f, base - h * 0.06f)
            close()
        }
        withTransform({ scale(1f, 0.62f, pivot = Offset(w / 2, base)) }) {
        drawPath(body, Brush.verticalGradient(listOf(Color(0xFF9FB4CF).copy(alpha = 0.38f), Color(0xFF5E7392).copy(alpha = 0.30f)), startY = base - h * 0.37f, endY = base))
        // Windows
        val win = Path().apply {
            moveTo(w * 0.37f, base - h * 0.23f); lineTo(w * 0.42f, base - h * 0.32f); lineTo(w * 0.53f, base - h * 0.32f); lineTo(w * 0.53f, base - h * 0.23f); close()
            moveTo(w * 0.56f, base - h * 0.23f); lineTo(w * 0.56f, base - h * 0.32f); lineTo(w * 0.63f, base - h * 0.32f); lineTo(w * 0.70f, base - h * 0.23f); close()
        }
        drawPath(win, Color(0xFF16233A).copy(alpha = 0.45f))
        for (x in listOf(0.28f, 0.72f)) {
            drawCircle(Color(0xFF0E1726).copy(alpha = 0.7f), radius = w * 0.075f, center = Offset(w * x, base - h * 0.05f))
            drawCircle(Color(0xFF8A9BB5).copy(alpha = 0.6f), radius = w * 0.035f, center = Offset(w * x, base - h * 0.05f))
        }
        }

        // The wand at the top right and its spray fanning onto the car.
        val nozzle = Offset(w * 0.86f, h * 0.10f)
        drawLine(Color(0xFFB8C4D6).copy(alpha = 0.6f), Offset(w * 0.98f, h * 0.02f), nozzle, strokeWidth = w * 0.03f, cap = StrokeCap.Round)
        val drops = 5 + level * 2
        for (k in 0 until drops) {
            val p = (fall + k.toFloat() / drops) % 1f
            val spread = (k % 5 - 2) * 0.07f
            val x = nozzle.x + (w * (-0.30f + spread)) * p
            val y = nozzle.y + (base - h * 0.22f - nozzle.y) * p * p + h * 0.02f * sin(p * 6f)
            drawLine(cyan.copy(alpha = 0.75f * (1f - p * 0.4f)), Offset(x, y), Offset(x - w * 0.015f, y + h * 0.035f), strokeWidth = w * 0.014f, cap = StrokeCap.Round)
        }
        // Foam on the car.
        for (k in 0 until 4 + level) {
            val phase = (bob + k * 0.23f) % 1f
            val cx = w * (0.20f + 0.14f * (k % 5))
            val cy = base - h * (0.22f + 0.08f * phase)
            drawCircle(Color.White.copy(alpha = 0.35f * (1f - phase)), radius = w * (0.022f + 0.008f * (k % 3)), center = Offset(cx, cy), style = Stroke(w * 0.008f))
        }
        // A soft wet sheen on the ground.
        drawRoundRect(cyan.copy(alpha = 0.12f), topLeft = Offset(w * 0.06f, base - h * 0.01f), size = Size(w * 0.88f, h * 0.03f), cornerRadius = CornerRadius(h * 0.015f))
    }
}

/**
 * Puts [WashCardScene] behind the price / name / minutes of each package card (a View
 * layout; the scene is inserted as the cards' first, untouchable child).
 */
fun ConstraintLayout.addWashScene(level: Int) {
    val scene = ComposeView(context).apply {
        id = View.generateViewId()
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setContent { WashCardScene(level) }
    }
    addView(scene, 0, ConstraintLayout.LayoutParams(0, 0).apply {
        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
    })
}
