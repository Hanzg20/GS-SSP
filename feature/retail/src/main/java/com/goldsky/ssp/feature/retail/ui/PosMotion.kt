package com.goldsky.ssp.feature.retail.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Small motion / polish pieces for the staff POS screens (Ourea, dine-in floor).
 * All theme-aware (the shared GoldSkyTheme has a dark and a light mode), all drawn,
 * none of them blocks a tap: a cashier taps fast, so nothing here waits on an animation.
 */

/** Every style with tabular (equal-width) digits: totals and change don't shift as they change. */
fun Typography.withTabularNumbers(): Typography {
    fun TextStyle.t() = copy(fontFeatureSettings = "tnum")
    return copy(
        displayLarge = displayLarge.t(), displayMedium = displayMedium.t(), displaySmall = displaySmall.t(),
        headlineLarge = headlineLarge.t(), headlineMedium = headlineMedium.t(), headlineSmall = headlineSmall.t(),
        titleLarge = titleLarge.t(), titleMedium = titleMedium.t(), titleSmall = titleSmall.t(),
        bodyLarge = bodyLarge.t(), bodyMedium = bodyMedium.t(), bodySmall = bodySmall.t(),
        labelLarge = labelLarge.t(), labelMedium = labelMedium.t(), labelSmall = labelSmall.t(),
    )
}

/**
 * The "glass" card: a soft layered shadow, the surface colour and a 1 dp hairline
 * (white 8 % on the dark theme, ink 8 % on the light one) so a card reads as lifted
 * off the background instead of a flat block.
 */
fun Modifier.glassCard(shape: Shape = RoundedCornerShape(16.dp), elevation: Dp = 3.dp, color: Color? = null): Modifier = composed {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val hairline = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f))
        .clip(shape)
        .background(color ?: scheme.surface)
        .border(1.dp, hairline, shape)
}

/** The 1 dp hairline of [glassCard], for dialogs and panels drawn by other components. */
@Composable
fun hairlineColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)

/** A border that slowly breathes in [color] while [enabled] (e.g. a table waiting to pay). */
fun Modifier.breathingBorder(color: Color, enabled: Boolean, shape: Shape, width: Dp = 2.dp): Modifier =
    if (!enabled) this else composed {
        val t = rememberInfiniteTransition(label = "breathe")
        val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "breatheAlpha")
        this
            .shadow((6 * a).dp, shape, clip = false, ambientColor = color, spotColor = color)
            .border(width, color.copy(alpha = a), shape)
    }

/** A light band sweeping across a placeholder block while data loads. */
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
    val scheme = MaterialTheme.colorScheme
    val base = scheme.onSurface.copy(alpha = 0.07f)
    val shine = scheme.onSurface.copy(alpha = 0.16f)
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "shimmerX")
    this.clip(shape).background(base).drawBehind {
        val w = size.width
        drawRect(Brush.linearGradient(listOf(Color.Transparent, shine, Color.Transparent), start = Offset(w * x - w * 0.5f, 0f), end = Offset(w * x, size.height)))
    }
}

/** A placeholder card shaped like the content that is about to arrive. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, lines: Int = 3) {
    Column(modifier.glassCard(RoundedCornerShape(14.dp), elevation = 1.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 110.dp, height = 14.dp).shimmer())
        repeat((lines - 2).coerceAtLeast(0)) { Box(Modifier.size(width = 80.dp, height = 10.dp).shimmer()) }
        Box(Modifier.size(width = 56.dp, height = 16.dp).shimmer())
    }
}

/** What an empty list shows: a small line drawing, a title and (optionally) what to do next. */
enum class EmptyArt { CART, RECEIPT, BOX, TABLE, CHART }

@Composable
fun EmptyState(art: EmptyArt, title: String, hint: String? = null, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyArtwork(art, scheme.primary, scheme.onSurfaceVariant, Modifier.size(96.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center)
        hint?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = scheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun EmptyArtwork(art: EmptyArt, accent: Color, ink: Color, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "emptyArt")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "bob")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val line = Stroke(w * 0.03f, cap = StrokeCap.Round)
        val lineInk = ink.copy(alpha = 0.55f)
        // A soft disc behind every drawing.
        drawCircle(accent.copy(alpha = 0.08f), w * 0.48f, Offset(w / 2, h / 2))
        val dy = -h * 0.03f * bob
        when (art) {
            EmptyArt.CART -> {
                drawLine(lineInk, Offset(w * 0.18f, h * 0.30f + dy), Offset(w * 0.30f, h * 0.30f + dy), line.width, StrokeCap.Round)
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.30f, h * 0.30f + dy); lineTo(w * 0.38f, h * 0.62f + dy); lineTo(w * 0.74f, h * 0.62f + dy); lineTo(w * 0.80f, h * 0.38f + dy); lineTo(w * 0.33f, h * 0.38f + dy)
                }
                drawPath(p, lineInk, style = line)
                drawCircle(accent, w * 0.045f, Offset(w * 0.42f, h * 0.74f + dy))
                drawCircle(accent, w * 0.045f, Offset(w * 0.70f, h * 0.74f + dy))
            }
            EmptyArt.RECEIPT -> {
                val l = w * 0.30f; val r = w * 0.70f; val top = h * 0.20f + dy; val bot = h * 0.78f + dy
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(l, top); lineTo(r, top); lineTo(r, bot)
                    // Torn edge.
                    var x = r; var up = true
                    while (x > l) { x -= w * 0.05f; lineTo(x.coerceAtLeast(l), if (up) bot - h * 0.04f else bot); up = !up }
                    close()
                }
                drawPath(p, lineInk, style = line)
                for (k in 0 until 3) drawLine(lineInk, Offset(l + w * 0.07f, top + h * (0.12f + 0.11f * k)), Offset(r - w * (0.07f + 0.08f * (k % 2)), top + h * (0.12f + 0.11f * k)), line.width, StrokeCap.Round)
                drawLine(accent, Offset(l + w * 0.07f, top + h * 0.45f), Offset(r - w * 0.07f, top + h * 0.45f), line.width * 1.4f, StrokeCap.Round)
            }
            EmptyArt.BOX -> {
                val top = h * 0.36f + dy
                drawRoundRect(lineInk, Offset(w * 0.24f, top), Size(w * 0.52f, h * 0.40f), CornerRadius(w * 0.04f), style = line)
                drawLine(lineInk, Offset(w * 0.24f, top), Offset(w * 0.16f, top - h * 0.10f), line.width, StrokeCap.Round)
                drawLine(lineInk, Offset(w * 0.76f, top), Offset(w * 0.84f, top - h * 0.10f), line.width, StrokeCap.Round)
                drawLine(accent, Offset(w * 0.42f, top + h * 0.12f), Offset(w * 0.58f, top + h * 0.12f), line.width * 1.4f, StrokeCap.Round)
            }
            EmptyArt.TABLE -> {
                val top = h * 0.42f + dy
                drawLine(lineInk, Offset(w * 0.20f, top), Offset(w * 0.80f, top), line.width * 1.3f, StrokeCap.Round)
                drawLine(lineInk, Offset(w * 0.30f, top), Offset(w * 0.30f, h * 0.76f + dy), line.width, StrokeCap.Round)
                drawLine(lineInk, Offset(w * 0.70f, top), Offset(w * 0.70f, h * 0.76f + dy), line.width, StrokeCap.Round)
                drawCircle(accent, w * 0.06f, Offset(w * 0.50f, top - h * 0.10f), style = line)
            }
            EmptyArt.CHART -> {
                val base = h * 0.74f + dy
                drawLine(lineInk, Offset(w * 0.22f, base), Offset(w * 0.78f, base), line.width, StrokeCap.Round)
                listOf(0.18f, 0.30f, 0.12f).forEachIndexed { k, f ->
                    val x = w * (0.32f + 0.18f * k)
                    drawLine(if (k == 1) accent else lineInk, Offset(x, base - h * 0.04f), Offset(x, base - h * (0.04f + f)), w * 0.07f, StrokeCap.Round)
                }
            }
        }
    }
}

/**
 * Concentric rings spreading from the middle -- "tap your card here" / "waiting for
 * the scan". [content] sits in the centre (an icon).
 */
@Composable
fun PaymentRadar(color: Color, modifier: Modifier = Modifier, size: Dp = 120.dp, content: @Composable () -> Unit = {}) {
    val t = rememberInfiniteTransition(label = "radar")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            for (k in 0..2) {
                val p = (ring + k / 3f) % 1f
                drawCircle(color.copy(alpha = (1f - p) * 0.55f), r * (0.32f + 0.68f * p), style = Stroke(2.dp.toPx()))
            }
            drawCircle(color.copy(alpha = 0.16f), r * 0.32f)
        }
        content()
    }
}
