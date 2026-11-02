package com.goldsky.ssp.feature.apex

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.payment.ApexMascotPrefs
import kotlinx.coroutines.delay

/**
 * 小海狸's animated look (docs/apex_agent_design.md, P1). What it is doing is app-wide
 * ([mood]): the chat sets it, and the sidebar / tab-bar mascot shows it too.
 *
 * Pictures: apex_beaver_<mood>.png (idle, blink, listening, thinking, done) when they
 * are in res/drawable-nodpi, else the one apex_beaver.png with the motion drawn in code.
 * Still when [animate] is false (a payment on screen) or the store turned it off
 * (Settings, ApexMascotPrefs).
 */
object ApexMascot {
    enum class Mood { IDLE, LISTENING, THINKING, DONE }

    var mood by mutableStateOf(Mood.IDLE)
        private set
    private var doneSeq by mutableStateOf(0)

    fun listening() { mood = Mood.LISTENING }
    fun thinking() { mood = Mood.THINKING }
    fun idle() { mood = Mood.IDLE }
    /** "Done" for a moment, then back to idle. */
    fun done() { mood = Mood.DONE; doneSeq++ }

    internal val doneTick: Int get() = doneSeq

    /** Things worth a look (P3), polled by the mascot in the tab bar / sidebar. */
    var alerts by mutableStateOf<List<com.goldsky.ssp.payment.ApexRepository.Alert>>(emptyList())
        private set

    suspend fun refreshAlerts() {
        com.goldsky.ssp.payment.ApexRepository.alerts()?.let { alerts = it }
    }
}

private val Cyan = Color(0xFF00E5FF)
private val Green = Color(0xFF22C55E)

/** The drawable for a picture of 小海狸 if the app has it (0 = not there). */
@Composable
private fun picture(name: String): Int {
    val context = LocalContext.current
    return remember(name) { context.resources.getIdentifier("apex_beaver_$name", "drawable", context.packageName) }
}

@Composable
fun BeaverMascot(size: Dp, modifier: Modifier = Modifier, animate: Boolean = true, selected: Boolean = false, pollAlerts: Boolean = false) {
    val context = LocalContext.current
    val on = animate && remember { ApexMascotPrefs.animated(context) }
    val mood = if (on) ApexMascot.mood else ApexMascot.Mood.IDLE

    // The one that polls (tab bar / sidebar): every 5 minutes while it is on screen.
    if (pollAlerts) LaunchedEffect(Unit) { while (true) { ApexMascot.refreshAlerts(); delay(5 * 60_000L) } }
    val alertCount = ApexMascot.alerts.size

    // Done: back to idle after a moment.
    val tick = ApexMascot.doneTick
    LaunchedEffect(tick) { if (ApexMascot.mood == ApexMascot.Mood.DONE) { delay(1400); ApexMascot.idle() } }

    // Idle: a slow bob, and a blink every few seconds (only with a closed-eyes picture).
    val t = rememberInfiniteTransition(label = "beaver")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    var blink by remember { mutableStateOf(false) }
    val blinkPic = picture("blink")
    LaunchedEffect(on, blinkPic) {
        while (on && blinkPic != 0) { delay(4200 + (0..2500).random().toLong()); blink = true; delay(160); blink = false }
    }

    val alertPic = picture("alert")
    val pic = when {
        mood == ApexMascot.Mood.IDLE && alertCount > 0 && alertPic != 0 -> alertPic
        mood == ApexMascot.Mood.IDLE && blink && blinkPic != 0 -> blinkPic
        else -> picture(mood.name.lowercase()).takeIf { it != 0 } ?: picture("idle").takeIf { it != 0 } ?: R.drawable.apex_beaver
    }
    val lift = with(LocalDensity.current) { (size.toPx() * 0.04f) }
    val popScale by animateFloatAsState(if (mood == ApexMascot.Mood.DONE) 1.12f else 1f, spring(dampingRatio = 0.45f), label = "pop")

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // Ring: thinking = a turning arc, listening = a breathing cyan ring, selected = a quiet one.
        Canvas(Modifier.matchParentSize()) {
            val w = this.size.minDimension * 0.06f
            when {
                mood == ApexMascot.Mood.THINKING ->
                    drawArc(Cyan, startAngle = spin, sweepAngle = 110f, useCenter = false, style = Stroke(w, cap = StrokeCap.Round))
                mood == ApexMascot.Mood.LISTENING ->
                    drawCircle(Cyan.copy(alpha = 0.35f + 0.55f * pulse), radius = this.size.minDimension / 2 - w / 2, style = Stroke(w * (1f + pulse)))
                selected -> drawCircle(Cyan.copy(alpha = 0.6f), radius = this.size.minDimension / 2 - w / 2, style = Stroke(w * 0.7f))
            }
        }
        // The old single picture has a white background: show it in a white circle. The mood
        // pictures are cut out (transparent), so 小海狸 stands on whatever is behind him.
        val legacy = pic == R.drawable.apex_beaver
        Image(
            painterResource(pic), contentDescription = null,
            modifier = Modifier
                .size(size * if (legacy) 0.86f else 0.96f)
                .graphicsLayer {
                    translationY = if (on && mood == ApexMascot.Mood.IDLE) -lift * bob else 0f
                    scaleX = popScale; scaleY = popScale
                }
                .then(if (legacy) Modifier.clip(CircleShape).background(Color.White) else Modifier)
        )
        // Something worth a look: how many, on his sign's corner.
        if (alertCount > 0 && mood == ApexMascot.Mood.IDLE) {
            Box(
                Modifier.align(Alignment.TopEnd).size(size * 0.38f).clip(CircleShape).background(Color(0xFFF97316)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Text(alertCount.toString(), color = Color.White,
                    fontSize = with(LocalDensity.current) { (size * 0.24f).toSp() }, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
        if (mood == ApexMascot.Mood.DONE) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.36f).clip(CircleShape).background(Green),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(size * 0.26f)) }
        }
    }
}
