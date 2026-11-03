package com.goldsky.ssp.vending.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.vending.ui.theme.KioskCyan

/**
 * The contactless mark (four arcs), drawn, pulsing outward to draw the hand to the
 * reader. Drawn rather than a bitmap so it stays sharp at any size.
 */
@Composable
fun NfcRadar(size: Dp = 120.dp, color: Color = KioskCyan) {
    val t = rememberInfiniteTransition(label = "NfcRadar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse")
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val c = Offset(w * 0.18f, w / 2)
        val stroke = w * 0.075f
        // Pulse ring behind the mark
        drawCircle(color.copy(alpha = 0.25f * (1 - p)), radius = w * (0.25f + 0.25f * p), center = Offset(w / 2, w / 2))
        for (i in 0 until 4) {
            val r = w * (0.16f + i * 0.16f)
            // Each arc lights up in turn as the pulse passes it.
            val lit = ((p * 4 - i).let { if (it in 0f..1f) 1f - it else 0f })
            drawArc(
                color = color.copy(alpha = 0.45f + 0.55f * lit),
                startAngle = -50f, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}
