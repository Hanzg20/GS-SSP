package com.goldsky.ssp.feature.timer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.feature.timer.TimerViewModel.Screen

// Unattended-kiosk palette (see .cursorrules): navy base, amber/emerald accents.
private val Bg = Color(0xFF121824)
private val Surface = Color(0xFF1B2436)
private val SurfaceHi = Color(0xFF243049)
private val Amber = Color(0xFFFFB800)
private val Emerald = Color(0xFF10B981)
private val Coral = Color(0xFFF43F5E)
private val TextHi = Color(0xFFE6EDF7)
private val TextLo = Color(0xFF8A97AD)

@Composable
fun TimerApp(
    s: TimerViewModel.UiState,
    version: String,
    onSelect: (TimerPackage) -> Unit,
    onCancelPayment: () -> Unit,
    onTechTrigger: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        AnimatedContent(
            targetState = s.screen,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(s, version, onSelect, onTechTrigger)
                is Screen.Paying -> PayingScreen(screen, s.demoMode, onCancelPayment)
                is Screen.Running -> RunningScreen(screen, s.now)
                is Screen.Finished -> ResultScreen(Emerald, "✓", "Time's up", "Thank you — see you next time!")
                is Screen.Declined -> ResultScreen(Coral, "✕", "Payment not completed", screen.message + "\nPlease try again.")
                is Screen.StartFailed -> ResultScreen(
                    Coral, "!", "Machine could not start",
                    when (screen.refunded) {
                        null -> "Reversing your payment…\nIf the card reader asks, present your card again."
                        true -> "Your payment has been reversed.\nNo charge was made."
                        false -> "We couldn't reverse the payment automatically.\nPlease contact the attendant."
                    },
                )
            }
        }
        if (s.demoMode) {
            Text(
                "DEMO", color = Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
                    .background(Amber, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

// ---- Home ---------------------------------------------------------------

@Composable
private fun HomeScreen(
    s: TimerViewModel.UiState,
    version: String,
    onSelect: (TimerPackage) -> Unit,
    onTechTrigger: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                s.subtitle?.let { Text(it, color = TextLo, fontSize = 12.sp, maxLines = 1) }
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (s.healthy) Emerald else Coral))
        }

        Spacer(Modifier.height(14.dp))
        val productName = s.packages.map { it.name }.distinct().singleOrNull() ?: "Self-Service"
        Text(productName, color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text("Choose your time", color = TextLo, fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (s.packages.isEmpty()) {
                Text("Loading…", color = TextLo, modifier = Modifier.align(Alignment.Center))
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    s.packages.take(3).forEach { pkg ->
                        PackageCard(pkg, enabled = !s.locked, modifier = Modifier.weight(1f).fillMaxHeight()) { onSelect(pkg) }
                    }
                }
            }
            if (s.locked) {
                Column(
                    Modifier.matchParentSize().background(Bg.copy(alpha = 0.92f), RoundedCornerShape(18.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text("Temporarily out of service", color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Sorry for the inconvenience", color = TextLo, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💳  Credit · Debit · Tap to pay", color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TripleTapText(version, onTechTrigger)
        }
    }
}

// No "best value"-style badges: per-minute pricing isn't monotonic across
// packages ($2/4min is cheaper per minute than $3/5min), so any such claim
// would have to be computed, and a wrong one misleads paying customers.
@Composable
private fun PackageCard(pkg: TimerPackage, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(2.dp, SurfaceHi, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(4.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatPrice(pkg.priceCents), color = Amber, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
            Text(formatDuration(pkg.durationSec), color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "START", color = Bg, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(Amber, RoundedCornerShape(10.dp)).padding(vertical = 9.dp),
        )
    }
}

/** Hidden technician entry: 3 taps within 2s on the version label (same gesture as wash). */
@Composable
private fun TripleTapText(version: String, onTriggered: () -> Unit) {
    var count by remember { mutableIntStateOf(0) }
    var last by remember { mutableLongStateOf(0L) }
    Text(
        "v$version", color = TextLo.copy(alpha = 0.6f), fontSize = 11.sp,
        modifier = Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val now = System.currentTimeMillis()
                count = if (now - last > 2000) 1 else count + 1
                last = now
                if (count >= 3) { count = 0; onTriggered() }
            }
            .padding(8.dp),
    )
}

// ---- Paying -------------------------------------------------------------

@Composable
private fun PayingScreen(p: Screen.Paying, demo: Boolean, onCancel: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "tap").animateFloat(
        1f, 1.12f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${formatPrice(p.pkg.priceCents)}  ·  ${formatDuration(p.pkg.durationSec)}", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(p.pkg.name, color = TextLo, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(150.dp).scale(pulse), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Amber.copy(alpha = 0.12f))
                // Contactless symbol: three nested arcs.
                for (i in 0..2) {
                    val r = size.minDimension * (0.16f + i * 0.1f)
                    drawArc(
                        Amber, startAngle = -45f, sweepAngle = 90f, useCenter = false,
                        topLeft = Offset(center.x - r - size.minDimension * 0.08f, center.y - r),
                        size = Size(r * 2, r * 2), style = Stroke(width = 9f, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(p.message, color = TextHi, fontSize = 18.sp, textAlign = TextAlign.Center)
        if (!demo) Text("Follow the prompts on the reader", color = TextLo, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        // A real sale can't be cancelled from here: WizarPOS's
        // cancelCurrentTransaction is a no-op (the P3 socket is blocked on the
        // sale), and PAYWizard's own screen -- which has a Cancel -- is in front
        // during payment anyway. Don't show a button that does nothing.
        if (demo) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Cancel", color = TextLo, fontSize = 16.sp)
            }
        } else {
            Text("To cancel, press Cancel on the card reader", color = TextLo, fontSize = 13.sp)
        }
    }
}

// ---- Running ------------------------------------------------------------

@Composable
private fun RunningScreen(r: Screen.Running, now: Long) {
    val remaining = (r.totalMs - (now - r.startedAt)).coerceAtLeast(0)
    val fraction = if (r.totalMs <= 0) 0f else remaining.toFloat() / r.totalMs
    val endingSoon = remaining <= 30_000
    val color = if (endingSoon) Amber else Emerald
    val animated by animateFloatAsState(fraction, tween(250, easing = LinearEasing), label = "ring")

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${r.pkg.name.uppercase()} ON", color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val stroke = 22.dp.toPx()
                drawArc(SurfaceHi, 0f, 360f, false, style = Stroke(stroke))
                drawArc(color, -90f, 360f * animated, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatClock(remaining), color = TextHi, fontSize = 72.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("remaining", color = TextLo, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (endingSoon) "Almost done — finish up!" else "Stops automatically when time runs out",
            color = if (endingSoon) Amber else TextLo, fontSize = 15.sp,
        )
    }
}

// ---- Results --------------------------------------------------------------

@Composable
private fun ResultScreen(accent: Color, glyph: String, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(110.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(glyph, color = accent, fontSize = 56.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = TextLo, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

// ---- Technician ---------------------------------------------------------

@Composable
fun PinDialog(onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(300.dp).background(Surface, RoundedCornerShape(16.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("技术员 PIN", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (pin.isEmpty()) "————" else "●".repeat(pin.length),
                color = if (wrong) Coral else Amber, fontSize = 26.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "✕", "0", "OK")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(width = 80.dp, height = 46.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (k == "OK") Amber else SurfaceHi)
                                .clickable {
                                    wrong = false
                                    when (k) {
                                        "✕" -> if (pin.isEmpty()) onDismiss() else pin = pin.dropLast(1)
                                        "OK" -> if (!onSubmit(pin)) { wrong = true; pin = "" }
                                        else -> if (pin.length < 8) pin += k
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(k, color = if (k == "OK") Bg else TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
fun TechScreen(
    demoMode: Boolean,
    onDemoChange: (Boolean) -> Unit,
    onExit: () -> Unit,
    selfTest: PaymentSelfTestViewModel.State,
    onRunSelfTest: () -> Unit,
    holdTest: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().background(Surface).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("技术员模式", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("演示模式", color = TextLo, fontSize = 13.sp)
            Switch(checked = demoMode, onCheckedChange = onDemoChange, modifier = Modifier.padding(horizontal = 6.dp).scale(0.8f))
            TextButton(onClick = onExit) { Text("返回", color = Amber) }
        }
        Text(
            if (demoMode) "演示模式开：客户流程跳过刷卡、不驱动输出、不写交易记录。重启应用自动关闭。"
            else "演示模式关：客户流程真实刷卡、真实驱动输出。",
            color = if (demoMode) Amber else TextLo, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        PaymentSelfTestPanel(selfTest, onRunSelfTest)
        Box(Modifier.weight(1f)) { holdTest() }
    }
}

@Composable
private fun PaymentSelfTestPanel(s: PaymentSelfTestViewModel.State, onRun: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).background(Surface, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("支付撤销自检", color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("真实刷卡 $1.00，成功后立即撤销", color = TextLo, fontSize = 11.sp)
            }
            Button(
                onClick = onRun, enabled = !s.running,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Bg),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(if (s.running) "进行中…" else "开始", fontSize = 13.sp) }
        }
        s.lines.takeLast(3).forEach { Text(it, color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2) }
    }
}

// ---- formatting ---------------------------------------------------------

internal fun formatPrice(cents: Int): String =
    if (cents % 100 == 0) "$${cents / 100}" else "$%d.%02d".format(cents / 100, cents % 100)

internal fun formatDuration(sec: Int): String =
    if (sec % 60 == 0) "${sec / 60} min" else "%d:%02d min".format(sec / 60, sec % 60)

internal fun formatClock(ms: Long): String {
    val total = (ms + 999) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
