package com.goldsky.ssp.feature.timer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.sin

/**
 * A self-service vacuum station, drawn (same idea as Aegis Laundry's machines: crisp at
 * any density, no bitmaps): a cabinet with a status light, its hose and nozzle. [running]:
 * air (and a bit of grit) streams into the nozzle and along the hose, the mouth swirls,
 * the light is green. [accent]: the bay's colour (green, amber in the last 30 s).
 */
@Composable
fun VacuumDrawing(running: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "vac")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "flow")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "breathe")

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Cabinet (left), hose arcing over to the nozzle (bottom right).
        val cabL = w * 0.10f; val cabT = h * 0.12f; val cabW = w * 0.38f; val cabH = h * 0.80f
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFFE9EEF5), Color(0xFF9BA7B9)), startY = cabT, endY = cabT + cabH),
            topLeft = Offset(cabL, cabT), size = Size(cabW, cabH), cornerRadius = CornerRadius(w * 0.06f)
        )
        drawRoundRect(Color(0xFF6E7B90), topLeft = Offset(cabL, cabT), size = Size(cabW, cabH), cornerRadius = CornerRadius(w * 0.06f), style = Stroke(w * 0.015f))
        // Screen and status light
        drawRoundRect(Color(0xFF1B2537), topLeft = Offset(cabL + cabW * 0.14f, cabT + cabH * 0.10f), size = Size(cabW * 0.72f, cabH * 0.16f), cornerRadius = CornerRadius(w * 0.02f))
        val glow = if (running) 1f else 0.35f + 0.25f * sin(breathe * 6.283f)
        drawCircle((if (running) accent else Color(0xFF5BC0EB)).copy(alpha = glow), radius = w * 0.05f, center = Offset(cabL + cabW / 2, cabT + cabH * 0.36f))
        // Grille
        for (i in 0 until 4) {
            val y = cabT + cabH * (0.55f + i * 0.08f)
            drawLine(Color(0xFF7D899C), Offset(cabL + cabW * 0.2f, y), Offset(cabL + cabW * 0.8f, y), strokeWidth = w * 0.012f, cap = StrokeCap.Round)
        }

        // Hose: out of the cabinet top, over, down to the nozzle.
        val start = Offset(cabL + cabW * 0.78f, cabT + cabH * 0.06f)
        val nozzle = Offset(w * 0.80f, h * 0.84f)
        val hose = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(w * 0.62f, h * -0.08f, w * 1.02f, h * 0.10f, w * 0.88f, h * 0.48f)
            cubicTo(w * 0.80f, h * 0.66f, nozzle.x + w * 0.02f, nozzle.y - h * 0.16f, nozzle.x, nozzle.y)
        }
        drawPath(hose, Color(0xFF1C2433), style = Stroke(w * 0.12f, cap = StrokeCap.Round))
        drawPath(hose, Color(0xFF55637C), style = Stroke(w * 0.075f, cap = StrokeCap.Round))
        // Nozzle
        drawRoundRect(Color(0xFF141B27), topLeft = Offset(nozzle.x - w * 0.13f, nozzle.y - h * 0.03f), size = Size(w * 0.26f, h * 0.10f), cornerRadius = CornerRadius(w * 0.04f))

        if (running) {
            // Air drawn in at the mouth: a swirl...
            rotate(spin, Offset(nozzle.x, nozzle.y + h * 0.075f)) {
                for (k in 0 until 3) {
                    rotate(k * 120f, Offset(nozzle.x, nozzle.y + h * 0.075f)) {
                        drawArc(accent.copy(alpha = 0.55f), 0f, 70f, false,
                            topLeft = Offset(nozzle.x - w * 0.09f, nozzle.y + h * 0.075f - w * 0.09f), size = Size(w * 0.18f, w * 0.18f),
                            style = Stroke(w * 0.03f, cap = StrokeCap.Round))
                    }
                }
            }
            // ...and grit travelling up the hose into the cabinet.
            val pm = PathMeasure().apply { setPath(hose, false) }
            val len = pm.length
            for (k in 0 until 6) {
                val d = len * (1f - ((flow + k / 6f) % 1f))
                val p = pm.getPosition(d)
                drawCircle(Color.White, radius = w * 0.03f, center = p)
            }
        }
    }
}
