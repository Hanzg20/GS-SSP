package com.goldsky.ssp.vending.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.goldsky.ssp.vending.ui.theme.*

/*
 * One moving picture per screen, showing what its words say (owner, 2026-11-03):
 * a card to the reader for "tap your card" / "pay by card", the machine's keypad for
 * "choose on the machine", an item falling for "dispensing", a check drawing itself
 * for "take your item". Drawn, in the VIP page's gold, so they stay sharp at any size.
 */

private fun loop(ms: Int) = infiniteRepeatable<Float>(tween(ms, easing = LinearEasing))

/** A card comes down onto the reader; the contactless waves light when it lands. */
@Composable
fun CardTapAnim(size: Dp) {
    val p by rememberInfiniteTransition(label = "card").animateFloat(0f, 1f, loop(1800), label = "p")
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        // The reader: a dark slab across the bottom.
        drawRoundRect(KioskCard, Offset(w * 0.12f, w * 0.70f), Size(w * 0.76f, w * 0.22f), CornerRadius(w * 0.06f))
        drawRoundRect(KioskCardEdge, Offset(w * 0.12f, w * 0.70f), Size(w * 0.76f, w * 0.22f), CornerRadius(w * 0.06f),
            style = Stroke(w * 0.015f))
        // 0..0.5 the card comes down, 0.5..0.85 it rests, then fades out at the top.
        val down = FastOutSlowInEasing.transform((p / 0.5f).coerceIn(0f, 1f))
        val y = w * (0.02f + 0.40f * down)
        val alpha = if (p > 0.85f) 1f - (p - 0.85f) / 0.15f else 1f
        drawRoundRect(KioskAmber.copy(alpha = alpha), Offset(w * 0.22f, y), Size(w * 0.56f, w * 0.34f), CornerRadius(w * 0.05f))
        drawRoundRect(KioskBg.copy(alpha = 0.35f * alpha), Offset(w * 0.22f, y + w * 0.07f), Size(w * 0.56f, w * 0.06f))
        drawRoundRect(KioskBg.copy(alpha = 0.5f * alpha), Offset(w * 0.29f, y + w * 0.18f), Size(w * 0.10f, w * 0.08f),
            CornerRadius(w * 0.015f))
        // Landed: waves out of both sides of the card.
        if (p in 0.5f..0.85f) {
            val lit = (p - 0.5f) / 0.35f
            for (i in 0 until 3) {
                val a = (lit * 3 - i).coerceIn(0f, 1f)
                val r = w * (0.08f + i * 0.06f)
                for (side in listOf(-1f, 1f)) {
                    val cx = if (side < 0) w * 0.20f else w * 0.80f
                    drawArc(KioskAmber.copy(alpha = a), if (side < 0) 130f else -50f, 100f, false,
                        Offset(cx - r, y + w * 0.17f - r), Size(r * 2, r * 2), style = Stroke(w * 0.025f, cap = StrokeCap.Round))
                }
            }
        }
    }
}

/** The vending machine's keypad, its keys pressed one after another. */
@Composable
fun KeypadAnim(size: Dp) {
    val p by rememberInfiniteTransition(label = "keypad").animateFloat(0f, 1f, loop(2400), label = "p")
    val order = remember { listOf(0, 4, 8, 10) }
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        // The machine's panel.
        drawRoundRect(KioskCard, Offset(w * 0.18f, w * 0.02f), Size(w * 0.64f, w * 0.96f), CornerRadius(w * 0.08f))
        drawRoundRect(KioskCardEdge, Offset(w * 0.18f, w * 0.02f), Size(w * 0.64f, w * 0.96f), CornerRadius(w * 0.08f),
            style = Stroke(w * 0.015f))
        val pressed = order[(p * order.size).toInt().coerceIn(0, order.size - 1)]
        val key = w * 0.15f
        val gap = w * 0.04f
        val left = w / 2 - (key * 3 + gap * 2) / 2
        val top = w * 0.10f
        for (i in 0 until 12) {
            val col = i % 3
            val row = i / 3
            val o = Offset(left + col * (key + gap), top + row * (key + gap))
            val on = i == pressed
            drawRoundRect(if (on) KioskAmber else Color(0xFF34343C), o, Size(key, key), CornerRadius(key * 0.25f))
            if (on) drawCircle(KioskAmber.copy(alpha = 0.3f), key * 0.9f, o + Offset(key / 2, key / 2))
        }
    }
}

/** An item falls from its slot into the tray. */
@Composable
fun ItemDropAnim(size: Dp) {
    val p by rememberInfiniteTransition(label = "drop").animateFloat(0f, 1f, loop(1400), label = "p")
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        // The slot at the top, the tray at the bottom.
        drawLine(KioskCardEdge, Offset(w * 0.25f, w * 0.12f), Offset(w * 0.75f, w * 0.12f), w * 0.04f, StrokeCap.Round)
        drawRoundRect(KioskCard, Offset(w * 0.15f, w * 0.78f), Size(w * 0.70f, w * 0.16f), CornerRadius(w * 0.05f))
        drawRoundRect(KioskCardEdge, Offset(w * 0.15f, w * 0.78f), Size(w * 0.70f, w * 0.16f), CornerRadius(w * 0.05f),
            style = Stroke(w * 0.015f))
        val fall = FastOutLinearInEasing.transform((p / 0.7f).coerceIn(0f, 1f))
        val y = w * (0.14f + 0.52f * fall)
        val alpha = if (p > 0.85f) 1f - (p - 0.85f) / 0.15f else 1f
        drawRoundRect(KioskAmber.copy(alpha = alpha), Offset(w * 0.38f, y), Size(w * 0.24f, w * 0.14f), CornerRadius(w * 0.03f))
        // Motion lines while it falls.
        if (fall in 0.15f..0.99f) for (dx in listOf(0.42f, 0.5f, 0.58f)) {
            drawLine(KioskAmber.copy(alpha = 0.35f), Offset(w * dx, y - w * 0.12f), Offset(w * dx, y - w * 0.03f),
                w * 0.015f, StrokeCap.Round)
        }
    }
}

/** A check that draws itself in a green disc (done), or a "!" that pops in (nothing taken). */
@Composable
fun ResultAnim(ok: Boolean, size: Dp) {
    val t = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(ok) { t.snapTo(0f); t.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val color = if (ok) KioskEmerald else KioskAmber
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val pop = (t.value / 0.4f).coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = 0.18f), w / 2 * pop)
        drawCircle(color, w * 0.36f * pop)
        val st = w * 0.08f
        val d = ((t.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
        if (ok) {
            val a = Offset(w * 0.33f, w * 0.52f)
            val b = Offset(w * 0.45f, w * 0.64f)
            val c = Offset(w * 0.68f, w * 0.38f)
            val first = (d / 0.4f).coerceIn(0f, 1f)
            drawLine(KioskBg, a, a + (b - a) * first, st, StrokeCap.Round)
            if (d > 0.4f) drawLine(KioskBg, b, b + (c - b) * ((d - 0.4f) / 0.6f), st, StrokeCap.Round)
        } else if (d > 0f) {
            drawLine(KioskBg, Offset(w / 2, w * 0.32f), Offset(w / 2, w * (0.32f + 0.22f * d)), st, StrokeCap.Round)
            drawCircle(KioskBg, st * 0.6f * d, Offset(w / 2, w * 0.68f))
        }
    }
}
