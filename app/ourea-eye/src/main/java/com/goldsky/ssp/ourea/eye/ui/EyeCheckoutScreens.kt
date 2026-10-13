package com.goldsky.ssp.ourea.eye.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.ourea.eye.CfdDisplayState
import com.goldsky.ssp.ui.theme.BrandBlue
import com.goldsky.ssp.ui.theme.GoldHighlight
import com.goldsky.ssp.ui.theme.BrandCyan
import com.goldsky.ssp.ui.theme.Navy600
import com.goldsky.ssp.ui.theme.Navy800
import com.goldsky.ssp.ui.theme.Navy900
import com.goldsky.ssp.ui.theme.Navy950
import com.goldsky.ssp.ui.theme.ScanFrameGold
import com.goldsky.ssp.ui.theme.Slate100
import com.goldsky.ssp.ui.theme.Slate400
import com.goldsky.ssp.ui.theme.SuccessOnDark
import kotlinx.coroutines.isActive
import kotlin.math.sin
import kotlin.random.Random

/*
 * Ourea Eye's customer-facing checkout, for the Q3mini's 480x480 screen: a
 * dark, quiet "boutique" look -- navy with a soft gold/cyan glow, gold for
 * money, bilingual labels (most customers read Chinese or English). Colours
 * come from the shared palette (core/ui theme/Color.kt); the gold is the
 * logo's (ScanFrameGold).
 */

private val Gold = ScanFrameGold
private val GoldLight = GoldHighlight
private val goldText = Brush.linearGradient(listOf(GoldLight, Gold))
private val glassBorder = Brush.linearGradient(listOf(Gold.copy(alpha = 0.8f), BrandCyan.copy(alpha = 0.6f)))

private fun money(cents: Int) = "$" + "%.2f".format(cents / 100.0)

/** Navy gradient with two soft glows; every checkout screen sits on it. */
@Composable
private fun EyeBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Navy900, Navy950)))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Full-canvas fills: a drawCircle here showed the circle's edge mid-screen.
            drawRect(Brush.radialGradient(listOf(Gold.copy(alpha = 0.18f), Color.Transparent), center = Offset(size.width * 0.85f, size.height * 0.05f), radius = size.width * 0.7f))
            drawRect(Brush.radialGradient(listOf(BrandCyan.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.05f, size.height * 0.95f), radius = size.width * 0.8f))
        }
        content()
    }
}

// --------------------------------------------------------------------------
// Cart
// --------------------------------------------------------------------------

@Composable
fun CartScreen(state: CfdDisplayState.Cart) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.size - 1)
    }
    val count = state.items.sumOf { it.qty }

    EyeBackdrop {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            // Header: mark, title, item count
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(com.goldsky.ssp.core.ui.R.drawable.goldsky_logo), null, Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Your Order", color = Slate100, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Surface(shape = RoundedCornerShape(50), color = Navy800, border = BorderStroke(0.5.dp, Gold.copy(alpha = 0.6f))) {
                    Text("$count items", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(10.dp))

            // Items, newest last and lit
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.items) { i, item -> CartLine(item.name, item.qty, item.priceCents, newest = i == state.items.lastIndex) }
            }
            Spacer(Modifier.height(10.dp))

            // Totals: glass panel, gradient edge, gold total
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.verticalGradient(listOf(Navy800.copy(alpha = 0.95f), Navy900.copy(alpha = 0.95f))))
                    .border(1.dp, glassBorder, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        SmallFigure("Subtotal", state.subtotalCents, Modifier.weight(1f))
                        SmallFigure("Tax", state.taxCents, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("Total", color = Slate100, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            money(state.totalCents), maxLines = 1, softWrap = false,
                            style = TextStyle(brush = goldText, fontSize = 40.sp, fontWeight = FontWeight.Black)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CartLine(name: String, qty: Int, priceCents: Int, newest: Boolean) {
    // The item just scanned glows briefly, so the customer sees what was added.
    val glow by animateFloatAsState(targetValue = if (newest) 1f else 0f, animationSpec = tween(600), label = "glow")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Navy800.copy(alpha = 0.75f + 0.25f * glow))
            .border(0.5.dp, if (newest) Gold.copy(alpha = 0.7f) else Navy600, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (newest) Gold else Navy600),
            contentAlignment = Alignment.Center
        ) {
            Text("$qty", color = if (newest) Navy950 else Slate100, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(name, color = Slate100, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(money(priceCents * qty), color = if (newest) Gold else Slate100, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SmallFigure(label: String, cents: Int, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = Slate400, fontSize = 11.sp)
        Text(money(cents), color = Slate100, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --------------------------------------------------------------------------
// Payment (PAYWizard's own card screen normally covers this)
// --------------------------------------------------------------------------

@Composable
fun PaymentScreen(state: CfdDisplayState.Payment) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ring")
    EyeBackdrop {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("AMOUNT DUE", color = Slate400, fontSize = 12.sp, letterSpacing = 2.sp)
            Text(money(state.amountCents), style = TextStyle(brush = goldText, fontSize = 52.sp, fontWeight = FontWeight.Black), maxLines = 1, softWrap = false)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    for (k in 0..1) {
                        val t = (ring + k * 0.5f) % 1f
                        drawCircle(BrandCyan.copy(alpha = (1f - t) * 0.6f), radius = size.minDimension / 2 * (0.45f + 0.55f * t), style = Stroke(width = 3.dp.toPx()))
                    }
                    drawCircle(Brush.radialGradient(listOf(BrandBlue.copy(alpha = 0.55f), BrandBlue.copy(alpha = 0.15f))), radius = size.minDimension * 0.27f)
                }
                Text(")))", color = Slate100, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(14.dp))
            Text("Tap, insert or swipe your card", color = Slate100, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Tap, insert or use your phone", color = Slate400, fontSize = 13.sp)
        }
    }
}

// --------------------------------------------------------------------------
// Thank you: drawn check, gold confetti, countdown ring
// --------------------------------------------------------------------------

/** How long the thank-you stays up (the screen manager holds back "idle" until then). */
const val THANK_YOU_MS = 10_000L

private class Flake(val x: Float, val speed: Float, val drift: Float, val phase: Float, val size: Float, val color: Color, val spin: Float)

@Composable
fun ThankYouScreen(paidCents: Int?) {
    // One shared clock for every animation on the page.
    var t by remember { mutableStateOf(0f) } // seconds since shown
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (isActive) {
            val now = withFrameMillis { it }
            t = (now - start) / 1000f
        }
    }
    val flakes = remember {
        val palette = listOf(Gold, GoldLight, BrandCyan, Slate100)
        List(46) {
            Flake(
                x = Random.nextFloat(), speed = 0.10f + Random.nextFloat() * 0.18f, drift = 0.02f + Random.nextFloat() * 0.04f,
                phase = Random.nextFloat() * 6.28f, size = 4f + Random.nextFloat() * 6f, color = palette[it % palette.size], spin = Random.nextFloat() * 360f
            )
        }
    }
    val ringIn = (t / 0.7f).coerceIn(0f, 1f)                  // ring sweeps in
    val check = ((t - 0.5f) / 0.6f).coerceIn(0f, 1f)          // then the check draws
    val textIn = ((t - 0.9f) / 0.6f).coerceIn(0f, 1f)         // then the words rise
    val left = 1f - (t * 1000f / THANK_YOU_MS).coerceIn(0f, 1f) // countdown

    EyeBackdrop {
        // Confetti falling through the whole screen (looping)
        Canvas(Modifier.fillMaxSize()) {
            flakes.forEach { f ->
                val y = ((f.speed * t + f.phase / 6.28f) % 1.2f - 0.1f) * size.height
                val x = (f.x + f.drift * sin(t * 1.6f + f.phase)) * size.width
                rotate(f.spin + t * 120f, pivot = Offset(x, y)) {
                    drawRect(f.color.copy(alpha = 0.85f), topLeft = Offset(x - f.size / 2, y - f.size), size = Size(f.size, f.size * 1.8f))
                }
            }
        }

        Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(132.dp).scale(0.85f + 0.15f * ringIn), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    // soft halo, breathing
                    drawCircle(Brush.radialGradient(listOf(SuccessOnDark.copy(alpha = 0.30f + 0.08f * sin(t * 2.4f)), Color.Transparent)), radius = r)
                    // gold ring sweeping in
                    drawArc(
                        Brush.sweepGradient(listOf(Gold, GoldLight, BrandCyan, Gold)), startAngle = -90f, sweepAngle = 360f * ringIn,
                        useCenter = false, topLeft = Offset(r * 0.18f, r * 0.18f), size = Size(r * 1.64f, r * 1.64f),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                    // the check, drawn stroke by stroke
                    val a = Offset(r * 0.62f, r * 1.02f); val b = Offset(r * 0.90f, r * 1.30f); val c = Offset(r * 1.42f, r * 0.74f)
                    val first = (check / 0.4f).coerceIn(0f, 1f); val second = ((check - 0.4f) / 0.6f).coerceIn(0f, 1f)
                    val stroke = 9.dp.toPx()
                    if (first > 0f) drawLine(SuccessOnDark, a, a + (b - a) * first, strokeWidth = stroke, cap = StrokeCap.Round)
                    if (second > 0f) drawLine(SuccessOnDark, b, b + (c - b) * second, strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.alpha(textIn).offset(y = (12 * (1 - textIn)).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Thank You", style = TextStyle(brush = goldText, fontSize = 36.sp, fontWeight = FontWeight.Black))
                if (paidCents != null && paidCents > 0) {
                    Spacer(Modifier.height(10.dp))
                    Surface(shape = RoundedCornerShape(50), color = Navy800.copy(alpha = 0.9f), border = BorderStroke(0.5.dp, Gold.copy(alpha = 0.6f))) {
                        Text("Paid  ${money(paidCents)}", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("See you again soon", color = Slate400, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }

        // Countdown: a thin ring in the corner, emptying over the stay
        Canvas(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(22.dp)) {
            drawCircle(Navy600, style = Stroke(width = 2.dp.toPx()))
            drawArc(Gold, -90f, 360f * left, useCenter = false, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

