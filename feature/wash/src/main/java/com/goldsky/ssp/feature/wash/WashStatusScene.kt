package com.goldsky.ssp.feature.wash

import android.view.View
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.ComposeView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The three screens after a wash is paid for. */
enum class WashPhase { APPROVED, WASHING, DONE }

/** Driven by MainActivity.startFinalizationSequence; [progress] is the real pulse progress 0..1. */
class WashStatusState {
    var view: View? = null
    var phase by mutableStateOf(WashPhase.APPROVED)
    var progress by mutableFloatStateOf(0f)
}

private val Gold = Color(0xFFF5B23D)
private val Cyan = Color(0xFF5BD6F5)
private val Mint = Color(0xFF34D399)

/**
 * The picture above the status text after payment (replaces the breathing water drop):
 * approved -> a ring closing round a card and a check; washing -> a car under a wash arch
 * that travels across it with the real progress, water spraying, foam building; done ->
 * the car shining, sparkles, a check badge and falling drops. Drawn, not bitmaps.
 */
@Composable
fun WashStatusScene(state: WashStatusState) {
    val t = rememberInfiniteTransition(label = "washstatus")
    val clock by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "clock")
    val slow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "slow")
    val progress by animateFloatAsState(state.progress, tween(400), label = "progress")
    val intro = remember(state.phase) { Animatable(0f) }
    LaunchedEffect(state.phase) {
        intro.animateTo(1f, if (state.phase == WashPhase.DONE) spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow) else tween(700))
    }
    val flakes = remember { List(26) { Triple(Random.nextFloat(), 0.25f + Random.nextFloat() * 0.5f, Random.nextFloat()) } }

    Canvas(Modifier.fillMaxSize()) {
        when (state.phase) {
            WashPhase.APPROVED -> approved(intro.value, clock)
            WashPhase.WASHING -> washing(progress, clock, slow)
            WashPhase.DONE -> done(intro.value, clock, slow, flakes)
        }
    }
}

private fun DrawScope.approved(intro: Float, clock: Float) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension * 0.42f
    drawCircle(Gold.copy(alpha = 0.10f), r * 1.05f, c)
    drawArc(Gold, -90f, 360f * intro, false, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(r * 0.07f, cap = StrokeCap.Round))
    // A payment card, tilting in.
    val cw = r * 1.0f; val ch = r * 0.64f
    rotate(-14f * (1f - intro) + 3f * sin(clock * 2 * PI.toFloat()), c) {
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF162032)), Offset(c.x - cw / 2, c.y - ch / 2), Offset(c.x + cw / 2, c.y + ch / 2)),
            topLeft = Offset(c.x - cw / 2, c.y - ch / 2), size = Size(cw, ch), cornerRadius = CornerRadius(r * 0.08f))
        drawRoundRect(Gold.copy(alpha = 0.9f), topLeft = Offset(c.x - cw * 0.36f, c.y - ch * 0.18f), size = Size(cw * 0.2f, ch * 0.26f), cornerRadius = CornerRadius(r * 0.03f))
        drawLine(Color.White.copy(alpha = 0.35f), Offset(c.x - cw * 0.36f, c.y + ch * 0.25f), Offset(c.x + cw * 0.3f, c.y + ch * 0.25f), strokeWidth = r * 0.05f, cap = StrokeCap.Round)
    }
    // Check badge once the ring has closed.
    val k = ((intro - 0.6f) / 0.4f).coerceIn(0f, 1f)
    if (k > 0f) {
        val bc = Offset(c.x + r * 0.62f, c.y - r * 0.55f)
        drawCircle(Mint, r * 0.24f * k, bc)
        val p = Path().apply { moveTo(bc.x - r * 0.11f, bc.y); lineTo(bc.x - r * 0.02f, bc.y + r * 0.09f); lineTo(bc.x + r * 0.13f, bc.y - r * 0.08f) }
        drawPath(p, Color.White.copy(alpha = k), style = Stroke(r * 0.05f, cap = StrokeCap.Round))
    }
}

/** A side-view car whose body bottom sits on [base], [w] wide, centred on [cx]. */
private fun DrawScope.car(cx: Float, base: Float, w: Float, body: Color, shine: Float = 0f) {
    val l = cx - w / 2
    val h = w * 0.42f
    val p = Path().apply {
        moveTo(l, base - h * 0.15f); lineTo(l, base - h * 0.42f)
        quadraticBezierTo(l + w * 0.02f, base - h * 0.55f, l + w * 0.15f, base - h * 0.57f)
        lineTo(l + w * 0.27f, base - h * 0.92f)
        quadraticBezierTo(l + w * 0.33f, base - h, l + w * 0.45f, base - h)
        lineTo(l + w * 0.62f, base - h)
        quadraticBezierTo(l + w * 0.69f, base - h * 0.97f, l + w * 0.76f, base - h * 0.62f)
        quadraticBezierTo(l + w * 0.97f, base - h * 0.57f, l + w, base - h * 0.38f)
        lineTo(l + w, base - h * 0.15f); close()
    }
    drawPath(p, Brush.verticalGradient(listOf(body, body.copy(alpha = 0.75f)), startY = base - h, endY = base))
    val win = Path().apply {
        moveTo(l + w * 0.31f, base - h * 0.6f); lineTo(l + w * 0.36f, base - h * 0.88f); lineTo(l + w * 0.5f, base - h * 0.88f); lineTo(l + w * 0.5f, base - h * 0.6f); close()
        moveTo(l + w * 0.53f, base - h * 0.6f); lineTo(l + w * 0.53f, base - h * 0.88f); lineTo(l + w * 0.62f, base - h * 0.88f); lineTo(l + w * 0.7f, base - h * 0.6f); close()
    }
    drawPath(win, Color(0xFF0F1A2C).copy(alpha = 0.8f))
    if (shine > 0f) {
        // A highlight sweeping along the body.
        val x = l + w * shine
        drawLine(Color.White.copy(alpha = 0.55f), Offset(x - w * 0.06f, base - h * 0.95f), Offset(x + w * 0.04f, base - h * 0.2f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    }
    for (fx in listOf(0.24f, 0.78f)) {
        drawCircle(Color(0xFF0B1220), w * 0.085f, Offset(l + w * fx, base - h * 0.12f))
        drawCircle(Color(0xFFB8C4D6), w * 0.04f, Offset(l + w * fx, base - h * 0.12f))
    }
}

private fun DrawScope.washing(progress: Float, clock: Float, slow: Float) {
    val w = size.width; val h = size.height
    val base = h * 0.82f
    val carW = w * 0.78f
    car(w / 2, base, carW, Color(0xFF7F95B6))
    // Foam left behind the arch, building with progress.
    val l = w / 2 - carW / 2
    val archX = l - w * 0.05f + (carW + w * 0.1f) * progress
    for (k in 0 until 18) {
        val fx = l + carW * ((k * 0.137f) % 1f)
        if (fx > archX) continue
        val fy = base - carW * 0.42f * (0.45f + 0.5f * ((k * 0.29f) % 1f)) - h * 0.02f * sin((slow + k * 0.1f) * 6.28f)
        drawCircle(Color.White.copy(alpha = 0.75f), w * (0.018f + 0.01f * (k % 3)), Offset(fx, fy))
    }
    // The arch: two posts and a beam, travelling with the progress.
    val top = h * 0.12f
    drawLine(Color(0xFF3A4A66), Offset(archX, top), Offset(archX, base + h * 0.06f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(Gold, Offset(archX - w * 0.06f, top), Offset(archX + w * 0.06f, top), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
    // Spray fanning down from the beam.
    for (k in 0 until 9) {
        val p = (clock + k / 9f) % 1f
        val ang = (-30f + k * 7.5f) * PI.toFloat() / 180f
        val len = (base - top) * p
        val x = archX + sin(ang) * len * 0.5f
        val y = top + cos(ang) * len
        drawLine(Cyan.copy(alpha = 0.85f * (1f - p * 0.5f)), Offset(x, y), Offset(x, y + h * 0.04f), strokeWidth = w * 0.014f, cap = StrokeCap.Round)
    }
    // Wet floor.
    drawRoundRect(Cyan.copy(alpha = 0.18f), topLeft = Offset(w * 0.04f, base + h * 0.02f), size = Size(w * 0.92f, h * 0.04f), cornerRadius = CornerRadius(h * 0.02f))
}

private fun DrawScope.done(intro: Float, clock: Float, slow: Float, flakes: List<Triple<Float, Float, Float>>) {
    val w = size.width; val h = size.height
    // Falling drops all over.
    for ((x, speed, phase) in flakes) {
        val y = ((slow * speed * 2f + phase) % 1f) * h
        drawLine((if (phase > 0.5f) Cyan else Gold).copy(alpha = 0.6f), Offset(x * w, y), Offset(x * w, y + h * 0.03f), strokeWidth = w * 0.012f, cap = StrokeCap.Round)
    }
    val base = h * 0.8f
    val carW = w * 0.78f * (0.85f + 0.15f * intro)
    car(w / 2, base, carW, Color(0xFF9DB6DA), shine = clock)
    // Sparkles on the clean car.
    for (k in 0 until 4) {
        val tw = (sin((clock + k * 0.25f) * 2 * PI.toFloat()) + 1f) / 2f
        val sx = w / 2 - carW * 0.35f + carW * 0.23f * k
        val sy = base - carW * 0.42f * (0.6f + 0.25f * (k % 2))
        sparkle(Offset(sx, sy), w * 0.045f * tw)
    }
    // Check badge, springing in.
    val bc = Offset(w * 0.8f, h * 0.2f)
    val r = w * 0.12f * intro
    drawCircle(Mint, r, bc)
    val p = Path().apply { moveTo(bc.x - r * 0.45f, bc.y); lineTo(bc.x - r * 0.1f, bc.y + r * 0.38f); lineTo(bc.x + r * 0.5f, bc.y - r * 0.32f) }
    drawPath(p, Color.White, style = Stroke(r * 0.2f, cap = StrokeCap.Round))
    drawRoundRect(Cyan.copy(alpha = 0.18f), topLeft = Offset(w * 0.04f, base + h * 0.02f), size = Size(w * 0.92f, h * 0.04f), cornerRadius = CornerRadius(h * 0.02f))
}

private fun DrawScope.sparkle(c: Offset, r: Float) {
    if (r <= 0.5f) return
    val p = Path().apply {
        moveTo(c.x, c.y - r); quadraticBezierTo(c.x, c.y, c.x + r, c.y); quadraticBezierTo(c.x, c.y, c.x, c.y + r)
        quadraticBezierTo(c.x, c.y, c.x - r, c.y); quadraticBezierTo(c.x, c.y, c.x, c.y - r); close()
    }
    drawPath(p, Color.White)
}

private val scenes = java.util.WeakHashMap<ConstraintLayout, WashStatusState>()

/**
 * Puts [WashStatusScene] where the status icon is (larger, and the icon block moved up so
 * the text and progress bar below still fit the 480x480 screen) and hides the icon;
 * returns the state to drive it. Already added: the existing one. [WashStatusState.view]
 * is the scene, for hiding it when the error icon has to show instead.
 */
fun <O> ConstraintLayout.washStatusScene(icon: ImageView?, owner: O): WashStatusState
    where O : androidx.lifecycle.LifecycleOwner, O : androidx.savedstate.SavedStateRegistryOwner {
    scenes[this]?.let { return it }
    val state = WashStatusState()
    val scene = ComposeView(context).apply {
        id = View.generateViewId()
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setContent { WashStatusScene(state) }
    }
    // This overlay lives in its own window, which has no lifecycle owner, and Compose looks
    // the owner up on that window's ROOT view (not on the ComposeView): without this it
    // throws on attach. Crashed Wash right after approved card sales on 255, 2026-10-14
    // 19:07 and 19:13 (the first fix set it on the ComposeView only).
    for (v in listOf(this, rootView)) {
        v.setViewTreeLifecycleOwner(owner)
        v.setViewTreeSavedStateRegistryOwner(owner)
    }
    val px = (170 * resources.displayMetrics.density).toInt()
    icon?.let {
        (it.layoutParams as? ConstraintLayout.LayoutParams)?.let { ilp ->
            ilp.width = px; ilp.height = px; ilp.verticalBias = 0.22f; it.layoutParams = ilp
        }
        it.visibility = View.INVISIBLE
    }
    addView(scene, ConstraintLayout.LayoutParams(px, px).apply {
        if (icon != null && icon.id != View.NO_ID) {
            topToTop = icon.id; bottomToBottom = icon.id; startToStart = icon.id; endToEnd = icon.id
        } else {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID; startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
    })
    state.view = scene
    scenes[this] = state
    return state
}
