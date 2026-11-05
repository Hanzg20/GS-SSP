package com.goldsky.ssp.flex.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * A product tile's picture for firewood (products.attributes.art = "firewood", owner 2026-11-05:
 * "realistic, refined, lively"): [quantity] tied bundles of split logs, seen from the front so
 * the end grain shows -- bark, growth rings, a crack, a rope round each bundle -- in front of a
 * warm glow that breathes, on a bed of glowing coals with flames licking up behind the wood and
 * sparks drifting up (the "Ember" look, owner 2026-11-05). A bundle is the same size on every tile,
 * so 1, 2, 3 grow into a pile. Picked: the bundles hop and the fire comes up.
 * Drawn, not a bitmap, so it stays sharp on any screen and needs nothing from the network.
 */

private class Spark(val x: Float, val phase: Float, val speed: Float, val size: Float, val sway: Float)

/** A coal in the ember bed: where (fraction of the width / of the bed's depth), size, flicker rate and phase. */
private class Coal(val x: Float, val y: Float, val size: Float, val rate: Float, val phase: Float)

/**
 * One bundle: seven split pieces packed round (one in the middle, six about it), as a fraction of
 * the bundle's radius -- centre x, centre y, size. Drawn last to first, so the first is in front.
 */
private val BUNDLE = listOf(
    Triple(0f, 0f, 0.40f),
    Triple(0.62f, 0f, 0.40f), Triple(0.31f, 0.54f, 0.42f), Triple(-0.31f, 0.54f, 0.40f),
    Triple(-0.62f, 0f, 0.42f), Triple(-0.31f, -0.54f, 0.40f), Triple(0.31f, -0.54f, 0.41f),
)

@Composable
fun FirewoodArt(quantity: Int, selected: Boolean, modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "firewood")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "t")
    val breath by loop.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
    )
    // Picked: a hop, settling with a bounce.
    val lift by animateFloatAsState(
        if (selected) 1f else 0f, spring(dampingRatio = 0.35f, stiffness = 380f), label = "lift",
    )
    val heat by animateFloatAsState(if (selected) 1f else 0f, tween(500), label = "heat")
    val sparks = remember {
        val r = java.util.Random(7)
        List(16) { Spark(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 0.8f, 0.6f + r.nextFloat(), r.nextFloat() * 2f - 1f) }
    }
    // The coals' flicker: fast, so it reads as fire rather than a pulse.
    val flicker by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "flicker")
    val coals = remember {
        val r = java.util.Random(11)
        List(22) { Coal(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 0.8f, 1f + r.nextInt(3), r.nextFloat()) }
    }
    val bundles = quantity.coerceIn(1, 3)

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val ground = h * 0.92f

        // The glow behind: an ember-orange halo that breathes, brighter when picked.
        val glow = 0.20f + 0.10f * breath + 0.30f * heat
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFFF8A1F).copy(alpha = glow), Color(0xFFFF5A00).copy(alpha = glow * 0.35f), Color.Transparent),
                center = Offset(w / 2, ground - h * 0.30f), radius = maxOf(w, h) * 0.55f,
            ),
            radius = maxOf(w, h) * 0.55f, center = Offset(w / 2, ground - h * 0.30f),
        )
        // The ember bed under the wood: dark charcoal, its coals glowing and flickering.
        emberBed(Offset(w / 2, ground), w * 0.86f, h * 0.09f, coals, flicker, heat)

        // One bundle size for every tile (owner 2026-11-05: 1, 2, 3 must visibly grow): the size
        // three need -- two on the coals, one on top. A bundle reaches about 1.2 r from its centre.
        val r = minOf(w * 0.215f, h * 0.17f)
        val spots = when (bundles) {
            1 -> listOf(Offset(w / 2, ground - r * 1.0f))
            2 -> listOf(Offset(w / 2 - r * 1.02f, ground - r * 1.0f), Offset(w / 2 + r * 1.02f, ground - r * 1.0f))
            // Back (top) one first, so the front ones sit over it.
            else -> listOf(
                Offset(w / 2, ground - r * 2.72f),
                Offset(w / 2 - r * 1.02f, ground - r * 1.0f), Offset(w / 2 + r * 1.02f, ground - r * 1.0f),
            )
        }
        // Flames behind the wood, as wide as the pile and up past its top.
        val pileWidth = if (bundles == 1) r * 2.6f else r * 4.6f
        val pileTop = spots.minOf { it.y } - r * 1.2f
        flames(Offset(w / 2, ground - r * 0.3f), pileWidth, (ground - pileTop) + h * 0.16f + h * 0.10f * heat, flicker, heat, back = true)
        spots.forEachIndexed { i, c ->
            // Each bundle hops a little after the one before it.
            val hop = lift * r * 0.22f * (1f - i * 0.18f)
            val bob = sin((t + i * 0.33f) * 2 * PI).toFloat() * r * 0.015f
            val at = Offset(c.x, c.y - hop + bob)
            bundle(at, r, seed = i * 31 + 3, warmth = 0.25f * breath + 0.6f * heat)
            // Lit from below by the coals: the bundle's underside glows hot.
            val fl = 0.75f + 0.25f * sin((flicker * 3 + i * 0.4f) * 2 * PI).toFloat()
            drawCircle(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xFFFF6A10).copy(alpha = (0.22f + 0.25f * heat) * fl)),
                    startY = at.y - r * 0.2f, endY = at.y + r * 1.15f,
                ),
                radius = r * 1.12f, center = at,
            )
        }

        // Low tongues of flame in front, licking the foot of the wood.
        flames(Offset(w / 2, ground + r * 0.05f), pileWidth * 0.9f, r * (0.42f + 0.25f * heat), flicker, heat, back = false)

        // Embers drifting up and fading; more of them when picked.
        val shown = (8 + 8 * heat).toInt()
        for (k in 0 until shown) {
            val s = sparks[k]
            val p = ((t * s.speed + s.phase) % 1f)
            val x = w * (0.18f + 0.64f * s.x) + sin((p * 2.5f + s.phase) * 2 * PI).toFloat() * w * 0.04f * s.sway
            val y = ground - h * 0.25f - p * h * 0.70f
            // Flickers as it rises, and cools from white-yellow to deep red as it fades.
            val twinkle = 0.6f + 0.4f * sin((flicker * 9 + s.phase * 5) * 2 * PI).toFloat()
            val a = (1f - p) * (if (p < 0.1f) p / 0.1f else 1f) * twinkle
            val rad = w * 0.012f * s.size * (1f - p * 0.5f)
            val hot = lerp(Color(0xFFFFF1C2), Color(0xFFFF4A10), p)
            // A short trail below it, where it has just been.
            val prev = y + h * 0.05f
            val px = x - sin((p * 2.5f + s.phase) * 2 * PI).toFloat() * w * 0.008f * s.sway
            drawLine(
                Brush.verticalGradient(listOf(hot.copy(alpha = a * 0.7f), Color.Transparent), startY = y, endY = prev),
                Offset(x, y), Offset(px, prev), strokeWidth = rad * 1.1f, cap = StrokeCap.Round,
            )
            drawCircle(Color(0xFFFF6A1A).copy(alpha = a * 0.35f), rad * 2.8f, Offset(x, y))
            drawCircle(hot.copy(alpha = a), rad, Offset(x, y))
        }
    }
}

/**
 * Glowing coals in an oval bed centred on [c], [width] x [depth]: charcoal lumps whose cores
 * glow orange to yellow and flicker each at its own rate, under a low red heat haze.
 */
private fun DrawScope.emberBed(c: Offset, width: Float, depth: Float, coals: List<Coal>, flicker: Float, heat: Float) {
    // The heat haze over the bed.
    val haze = 0.30f + 0.25f * heat + 0.08f * sin(flicker * 4 * PI).toFloat()
    drawOval(
        Brush.radialGradient(listOf(Color(0xFFFF4A00).copy(alpha = haze), Color.Transparent), center = c, radius = width * 0.55f),
        topLeft = Offset(c.x - width * 0.6f, c.y - depth * 1.6f), size = Size(width * 1.2f, depth * 3.2f),
    )
    // The bed itself: charcoal, darker at the edge.
    drawOval(
        Brush.radialGradient(listOf(Color(0xFF3A1A0C), Color(0xFF140A06), Color.Transparent), center = c, radius = width * 0.5f),
        topLeft = Offset(c.x - width / 2, c.y - depth / 2), size = Size(width, depth),
    )
    for (coal in coals) {
        // Inside the oval.
        val dx = (coal.x - 0.5f) * width * 0.9f
        val span = kotlin.math.sqrt((1f - (2 * dx / width).let { it * it }).coerceAtLeast(0f))
        val at = Offset(c.x + dx, c.y + (coal.y - 0.5f) * depth * 0.8f * span)
        val size = depth * 0.30f * coal.size
        val f = (0.55f + 0.45f * sin((flicker * coal.rate + coal.phase) * 2 * PI).toFloat()) * (0.8f + 0.4f * heat)
        // The lump, then its glowing core.
        drawOval(Color(0xFF1C0D06), topLeft = Offset(at.x - size, at.y - size * 0.6f), size = Size(size * 2, size * 1.2f))
        drawOval(
            Brush.radialGradient(
                listOf(Color(0xFFFFE08A).copy(alpha = f), Color(0xFFFF7A1A).copy(alpha = f * 0.9f), Color(0xFFB02A08).copy(alpha = 0f)),
                center = at, radius = size,
            ),
            topLeft = Offset(at.x - size, at.y - size * 0.6f), size = Size(size * 2, size * 1.2f),
        )
    }
}

/**
 * Tongues of flame rising from [c] across [width], up to [height]: red at the edge, orange,
 * then a yellow-white core, each swaying and leaping at its own beat (whole beats per [flicker]
 * loop, so the loop is seamless). [back]: the tall ones behind the wood; else short ones in front.
 */
private fun DrawScope.flames(c: Offset, width: Float, height: Float, flicker: Float, heat: Float, back: Boolean) {
    val n = if (back) 7 else 5
    for (j in 0 until n) {
        val rel = if (n == 1) 0f else j / (n - 1f) - 0.5f
        val seed = j * 0.37f + if (back) 0f else 0.5f
        // Taller in the middle, each leaping on two beats of its own.
        val leap = 0.5f + 0.3f * sin((flicker * (3 + j % 3) + seed) * 2 * PI).toFloat() +
            0.2f * sin((flicker * (5 + j % 2) + seed * 2) * 2 * PI).toFloat()
        val fh = height * (1f - kotlin.math.abs(rel) * 0.9f) * (0.55f + 0.45f * leap)
        val bw = width / n * (if (back) 1.7f else 1.4f)
        val x = c.x + rel * width * 0.85f
        val sway = sin((flicker * (2 + j % 2) + seed * 3) * 2 * PI).toFloat() * bw * 0.45f
        val layers = listOf(
            Triple(1.00f, 1.00f, listOf(Color(0xFFFF3D00), Color(0xFFFF6A00))),
            Triple(0.66f, 0.78f, listOf(Color(0xFFFF8A00), Color(0xFFFFB300))),
            Triple(0.34f, 0.48f, listOf(Color(0xFFFFE27A), Color(0xFFFFF8E1))),
        )
        // In front they only lick the wood's foot: faint, so the wood stays readable.
        val alpha = (if (back) 0.85f else 0.40f) * (0.85f + 0.15f * heat)
        for ((wf, hf, cols) in layers) {
            val hw = bw * wf / 2
            val top = c.y - fh * hf
            val tip = Offset(x + sway * hf, top)
            val path = Path().apply {
                moveTo(x - hw, c.y)
                cubicTo(x - hw * 1.1f, c.y - fh * hf * 0.45f, tip.x - hw * 0.35f, c.y - fh * hf * 0.80f, tip.x, tip.y)
                cubicTo(tip.x + hw * 0.35f, c.y - fh * hf * 0.80f, x + hw * 1.1f, c.y - fh * hf * 0.45f, x + hw, c.y)
                close()
            }
            drawPath(
                path,
                Brush.verticalGradient(
                    listOf(cols[0].copy(alpha = 0f), cols[0].copy(alpha = alpha), cols[1].copy(alpha = alpha)),
                    startY = top, endY = c.y,
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.Plus,
            )
        }
    }
}

/** One tied bundle around [c], about [r] in radius. */
private fun DrawScope.bundle(c: Offset, r: Float, seed: Int, warmth: Float) {
    val rnd = java.util.Random(seed.toLong())
    val pieces = BUNDLE.map { (dx, dy, size) ->
        // Not a perfect honeycomb: each piece a little off and turned its own way.
        val at = Offset(c.x + (dx + (rnd.nextFloat() - 0.5f) * 0.06f) * r, c.y + (dy + (rnd.nextFloat() - 0.5f) * 0.06f) * r)
        Triple(at, size * r, rnd.nextFloat() * 360f)
    }
    for (i in pieces.indices.reversed()) piece(pieces[i].first, pieces[i].second, pieces[i].third, seed + i * 7, warmth)
    rope(pieces.map { it.first to it.second }, r)
}

/**
 * A split piece's end, as firewood is: a wedge from the heart of the log -- bark only on the
 * outer curve, growth rings round the pith at the wedge's point, a drying crack.
 */
private fun DrawScope.piece(c: Offset, size: Float, turn: Float, seed: Int, warmth: Float) {
    val rnd = java.util.Random(seed.toLong())
    val sweep = 95f + rnd.nextFloat() * 40f
    val radius = size * 1.38f
    // The pith sits behind the piece's middle, so the wedge is centred on [c].
    val mid = Math.toRadians((turn + sweep / 2).toDouble())
    val dir = Offset(cos(mid).toFloat(), sin(mid).toFloat())
    val pith = c - dir * (radius * 0.55f)
    fun wedge(rr: Float) = Path().apply {
        moveTo(pith.x, pith.y)
        arcTo(Rect(pith, rr), turn, sweep, false)
        close()
    }
    // Drop shadow onto what's behind.
    drawPath(wedge(radius).apply { translate(Offset(size * 0.08f, size * 0.12f)) }, Color.Black.copy(alpha = 0.5f))
    // Bark on the outer curve: dark, lit from the top-left.
    drawPath(wedge(radius), Brush.linearGradient(
        listOf(Color(0xFF6E4628), Color(0xFF3A2414), Color(0xFF1E1208)),
        start = Offset(c.x - size, c.y - size), end = Offset(c.x + size, c.y + size),
    ))
    // End grain: pale at the heart, deeper to the sapwood; warmer with the glow.
    val heart = lerp(Color(0xFFEFCB93), Color(0xFFFFDCA6), warmth)
    drawPath(wedge(radius * 0.86f), Brush.radialGradient(
        listOf(heart, Color(0xFFE0AA68), Color(0xFFBE7D40)), center = pith, radius = radius * 0.9f,
    ))
    // Growth rings round the pith.
    for (k in 1..6) {
        val rr = radius * 0.86f * k / 7f
        drawArc(
            Color(0xFF8A5528).copy(alpha = 0.22f + 0.08f * (k % 2)), turn, sweep, false,
            topLeft = Offset(pith.x - rr, pith.y - rr), size = Size(rr * 2, rr * 2), style = Stroke(size * 0.035f),
        )
    }
    // The heart wood's dark tip and a drying crack out from it.
    drawCircle(Color(0xFF7A4520), size * 0.07f, pith + dir * (size * 0.12f))
    val a = Math.toRadians((turn + sweep * (0.25f + rnd.nextFloat() * 0.5f)).toDouble())
    val crack = Offset(cos(a).toFloat(), sin(a).toFloat())
    drawLine(
        Color(0xFF4A2810).copy(alpha = 0.75f), pith + crack * (radius * 0.18f), pith + crack * (radius * 0.70f),
        strokeWidth = size * 0.05f, cap = StrokeCap.Round,
    )
    // Light catching the split faces' edge.
    drawPath(wedge(radius * 0.86f), Color.White.copy(alpha = 0.10f), style = Stroke(size * 0.04f))
}

/** Twine round the bundle, hugging its outline, with a knot on top. */
private fun DrawScope.rope(pieces: List<Pair<Offset, Float>>, r: Float) {
    val path = Path()
    var top = Offset(0f, Float.MAX_VALUE)
    for (k in 0..90) {
        val a = k / 90f * 2 * PI
        val u = Offset(cos(a).toFloat(), sin(a).toFloat())
        // The outline of a group of pieces: in each direction, the one reaching furthest.
        val far = pieces.maxBy { (c, size) -> c.x * u.x + c.y * u.y + size }
        val p = far.first + u * (far.second * 1.12f)
        if (p.y < top.y) top = p
        if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    val width = r * 0.055f
    drawPath(path, Color.Black.copy(alpha = 0.40f), style = Stroke(width * 1.8f))
    drawPath(path, Color(0xFFCDA56A), style = Stroke(width))
    // The twist: short dark strokes along it.
    drawPath(path, Color(0xFF6E4E24), style = Stroke(width * 0.6f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 0.7f, width * 0.8f))))
    // The knot and its two ends.
    drawCircle(Color(0xFF8C6A38), width * 1.3f, top)
    drawCircle(Color(0xFFD9B57A), width * 0.8f, top - Offset(width * 0.3f, width * 0.3f))
    for (side in listOf(-1f, 1f)) {
        drawLine(Color(0xFFCDA56A), top, top + Offset(side * r * 0.16f, -r * 0.10f), width * 0.9f, StrokeCap.Round)
    }
}

private fun lerp(a: Color, b: Color, f: Float) = Color(
    a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f,
)
