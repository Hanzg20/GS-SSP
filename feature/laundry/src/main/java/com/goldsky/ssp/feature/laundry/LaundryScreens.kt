package com.goldsky.ssp.feature.laundry

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.feature.laundry.LaundryViewModel.Screen
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/*
 * Aegis Laundry's customer screens, for the Q3mini's 480x480 display. Look taken from the
 * product renders in C:\goldsky\info\SSP (terminal-08-laundromat): dark slate, light
 * machine cards with a front-loader drawing, blue / teal accents. Every machine is drawn
 * here (no bitmaps): crisp at any density, and the drum can show water while running.
 */

private val Bg1 = Color(0xFF1E2836)
private val Bg2 = Color(0xFF0E141D)
private val Card = Color(0xFFF3F6FA)
private val CardEdge = Color(0xFFDDE3EC)
private val Ink = Color(0xFF13203A)
private val InkSoft = Color(0xFF5B6880)
private val OnDark = Color(0xFFF1F5F9)
private val OnDarkSoft = Color(0xFF93A1B8)
private val Blue = Color(0xFF2F6BFF)
private val Teal = Color(0xFF14C2AE)
private val TealDeep = Color(0xFF0E9C8C)
private val Busy = Color(0xFF4C5BD4)
private val Rose = Color(0xFFF0506E)
private val Amber = Color(0xFFF5B23D)

private fun money(cents: Int) = "$" + String.format(Locale.US, "%.2f", cents / 100.0)
private fun kindLabel(m: LaundryMachineConfig) = if (m.kind == LaundryMachineConfig.Kind.WASHER) "Washer" else "Dryer"

@Composable
fun LaundryApp(
    s: LaundryViewModel.UiState,
    onSelect: (LaundryMachineConfig) -> Unit,
    onBack: () -> Unit,
    onPay: () -> Unit,
    onCancelPayment: () -> Unit,
    onTechTrigger: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Bg1, Bg2)))) {
        // Soft glow behind the content, as on the renders.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Blue.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.9f, 0f), radius = size.width * 0.8f))
            drawRect(Brush.radialGradient(listOf(Teal.copy(alpha = 0.10f), Color.Transparent), center = Offset(0f, size.height), radius = size.width * 0.8f))
        }
        AnimatedContent(targetState = s.screen, contentKey = { it::class }, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) }, label = "laundry") { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(s, onSelect, onTechTrigger)
                is Screen.Confirm -> ConfirmScreen(screen.machine, onBack, onPay)
                is Screen.Paying -> PayingScreen(screen, onCancelPayment)
                is Screen.Starting -> StartingScreen(screen.machine)
                is Screen.Started -> StartedScreen(screen.machine)
                is Screen.Declined -> MessageScreen(Rose, "Payment not completed", screen.message)
                is Screen.Unavailable -> MessageScreen(Amber, "${screen.machine.name} is unavailable", "Nothing was charged. Please choose another machine or ask the attendant.")
                is Screen.StartFailed -> MessageScreen(
                    Rose, "Machine ${screen.machine.machineNo} did not start",
                    if (screen.paid) "Your payment is recorded. Please contact the attendant — do not pay again." else "Nothing was charged.",
                )
            }
        }
        if (s.demoMode || s.testArmedCents != null) {
            Text(
                if (s.testArmedCents != null) "CARD TEST ARMED" else "DEMO", color = Bg2, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Amber).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- home: the machine grid

@Composable
private fun HomeScreen(s: LaundryViewModel.UiState, onSelect: (LaundryMachineConfig) -> Unit, onTechTrigger: () -> Unit) {
    // Ticks once a minute for the "about N min left" estimates.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = System.currentTimeMillis() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(com.goldsky.ssp.core.ui.R.drawable.goldsky_logo), null,
                Modifier.size(30.dp).clickable(onClick = onTechTrigger)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("LAUNDRY", color = OnDark, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("Choose your machine", color = OnDarkSoft, fontSize = 12.sp)
            }
            ContactlessChip()
        }
        Spacer(Modifier.height(12.dp))
        if (s.machines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (s.configLoaded) "No machines set up yet" else "Loading…", color = OnDarkSoft, fontSize = 15.sp)
            }
        } else {
            val cols = if (s.machines.size <= 4) 2 else 4
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.machines.chunked(cols).forEach { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            val started = s.startedAt[m.machineNo]
                            val minsLeft = if (started != null && m.durationMin != null)
                                (m.durationMin - ((now - started) / 60_000).toInt()).takeIf { it > 0 } else null
                            MachineTile(
                                m, busy = s.busy(m), minsLeft = minsLeft, locked = s.locked,
                                modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { onSelect(m) }
                            )
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (s.locked) "Temporarily out of service" else "Pay here, then press START on your machine",
            color = if (s.locked) Rose else OnDarkSoft, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ContactlessChip() {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactlessIcon(Teal, Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Card · Tap · Pay", color = OnDark, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MachineTile(m: LaundryMachineConfig, busy: Boolean, minsLeft: Int?, locked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val available = !busy && !locked
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .shadow(if (available) 6.dp else 0.dp, shape)
            .clip(shape)
            .background(if (busy) Brush.verticalGradient(listOf(Busy, Busy.copy(alpha = 0.75f))) else Brush.verticalGradient(listOf(Color.White, Card)))
            .border(1.dp, if (busy) Color.White.copy(alpha = 0.2f) else CardEdge, shape)
            .clickable(enabled = available, onClick = onClick)
            .alpha(if (locked) 0.45f else 1f)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (busy) Color.White.copy(alpha = 0.2f) else Ink), contentAlignment = Alignment.Center) {
                Text("${m.machineNo}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            if (busy) Text("IN USE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
        MachineDrawing(m.kind, running = busy, modifier = Modifier.weight(1f).aspectRatio(0.82f).padding(vertical = 4.dp))
        Text(kindLabel(m), color = if (busy) Color.White.copy(alpha = 0.85f) else InkSoft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
            when {
                busy && minsLeft != null -> "~$minsLeft min"
                busy -> "Running"
                else -> money(m.priceCents)
            },
            color = if (busy) Color.White else Ink, fontSize = 17.sp, fontWeight = FontWeight.Black
        )
    }
}

/** A front-loader (washer) or a dryer, drawn. [running]: water / tumbling in the door. */
@Composable
fun MachineDrawing(kind: LaundryMachineConfig.Kind, running: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "drum")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "phase")
    val washer = kind == LaundryMachineConfig.Kind.WASHER
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val r = CornerRadius(w * 0.1f)
        // Body
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD8DEE8))), size = size, cornerRadius = r)
        drawRoundRect(Color(0xFFB9C2D0), size = size, cornerRadius = r, style = Stroke(w * 0.025f))
        // Control panel
        val panelH = h * 0.17f
        drawRoundRect(Color(0xFFE6EBF2), topLeft = Offset(w * 0.06f, h * 0.04f), size = Size(w * 0.88f, panelH), cornerRadius = CornerRadius(w * 0.05f))
        drawRoundRect(Color(0xFF243048), topLeft = Offset(w * 0.12f, h * 0.075f), size = Size(w * 0.32f, panelH * 0.42f), cornerRadius = CornerRadius(w * 0.02f))
        drawRoundRect(if (running) Teal else Color(0xFF4FD1C5).copy(alpha = 0.5f), topLeft = Offset(w * 0.15f, h * 0.095f), size = Size(w * 0.12f, panelH * 0.18f), cornerRadius = CornerRadius(w * 0.01f))
        drawCircle(Color(0xFF9AA6B8), radius = w * 0.055f, center = Offset(w * 0.78f, h * 0.04f + panelH / 2))
        drawCircle(Color(0xFFC5CDD9), radius = w * 0.035f, center = Offset(w * 0.62f, h * 0.04f + panelH / 2))
        // Door
        val c = Offset(w / 2, h * 0.6f)
        val outer = w * 0.36f
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF7F9FC), Color(0xFF9CA8BA)), center = c, radius = outer), radius = outer, center = c)
        drawCircle(Color(0xFF7D8AA0), radius = outer, center = c, style = Stroke(w * 0.02f))
        val glass = outer * 0.74f
        val glassBrush = if (washer) Brush.radialGradient(listOf(Color(0xFF5B7CB8), Color(0xFF1C2B4A)), center = c, radius = glass)
        else Brush.radialGradient(listOf(Color(0xFF6A6F7C), Color(0xFF262A33)), center = c, radius = glass)
        drawCircle(glassBrush, radius = glass, center = c)
        val doorPath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, glass)) }
        clipPath(doorPath) {
            if (washer) {
                val level = if (running) 0.15f else 0.45f
                drawWater(c, glass, level, phase, running)
            } else if (running) {
                rotate(phase * 360f, c) {
                    for (k in 0 until 3) rotate(k * 120f, c) {
                        drawRoundRect(Color(0xFFB0B8C8).copy(alpha = 0.7f), topLeft = Offset(c.x - glass * 0.5f, c.y - glass * 0.6f), size = Size(glass * 0.5f, glass * 0.3f), cornerRadius = CornerRadius(glass * 0.12f))
                    }
                }
            }
        }
        // Glass highlight
        drawArc(Color.White.copy(alpha = 0.35f), startAngle = 200f, sweepAngle = 70f, useCenter = false,
            topLeft = Offset(c.x - glass * 0.78f, c.y - glass * 0.78f), size = Size(glass * 1.56f, glass * 1.56f), style = Stroke(w * 0.03f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawWater(c: Offset, r: Float, level: Float, phase: Float, running: Boolean) {
    // Level: 0 = full, 1 = empty (fraction of the door from the top).
    val top = c.y - r + 2 * r * level
    val amp = r * if (running) 0.12f else 0.04f
    val path = Path().apply {
        moveTo(c.x - r, c.y + r)
        lineTo(c.x - r, top)
        val steps = 24
        for (i in 0..steps) {
            val x = c.x - r + 2 * r * i / steps
            val y = top + amp * sin((i.toFloat() / steps * 2f + phase * 2f) * PI.toFloat() * 2f)
            lineTo(x, y)
        }
        lineTo(c.x + r, c.y + r)
        close()
    }
    drawPath(path, Brush.verticalGradient(listOf(Color(0xFF7FD3F2).copy(alpha = 0.85f), Color(0xFF2D7FD0)), startY = top, endY = c.y + r))
    if (running) for (k in 0 until 5) {
        val bx = c.x - r * 0.6f + r * 0.3f * k
        val by = c.y + r * 0.7f - (r * 1.2f * ((phase + k * 0.21f) % 1f))
        drawCircle(Color.White.copy(alpha = 0.5f), radius = r * 0.06f, center = Offset(bx, by))
    }
}

@Composable
private fun ContactlessIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        for (i in 1..3) {
            val rr = size.minDimension * 0.3f * i
            drawArc(color, -45f, 90f, useCenter = false, topLeft = Offset(-rr * 0.6f, size.height / 2 - rr), size = Size(rr * 2, rr * 2), style = Stroke(size.minDimension * 0.12f, cap = StrokeCap.Round))
        }
    }
}

// ---------------------------------------------------------------- confirm

@Composable
private fun ConfirmScreen(m: LaundryMachineConfig, onBack: () -> Unit, onPay: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(170.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(170.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Blue.copy(alpha = 0.25f), Color.Transparent))))
                MachineDrawing(m.kind, running = false, modifier = Modifier.width(128.dp).aspectRatio(0.82f))
                Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(44.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                    Text("${m.machineNo}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(kindLabel(m).uppercase(), color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(m.name, color = OnDark, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text(money(m.priceCents), color = OnDark, fontSize = 40.sp, fontWeight = FontWeight.Black)
                m.durationMin?.let { Text("About $it minutes", color = OnDarkSoft, fontSize = 13.sp) }
                Spacer(Modifier.height(14.dp))
                Step(1, "Pay by card on this terminal")
                Spacer(Modifier.height(8.dp))
                Step(2, "Choose your cycle and press START on machine ${m.machineNo}")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(30.dp)).border(1.5.dp, OnDarkSoft.copy(alpha = 0.5f), RoundedCornerShape(30.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Text("Back", color = OnDark, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier.weight(2f).height(60.dp).shadow(8.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp))
                    .background(Brush.horizontalGradient(listOf(Teal, TealDeep))).clickable(onClick = onPay),
                contentAlignment = Alignment.Center
            ) { Text("Pay ${money(m.priceCents)}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text("$n", color = OnDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = OnDarkSoft, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

// ---------------------------------------------------------------- paying / starting / done

@Composable
private fun PayingScreen(p: Screen.Paying, onCancel: () -> Unit) {
    val m = p.machine
    val t = rememberInfiniteTransition(label = "pay")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ring")
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (p.test) {
            Text(
                "CARD TEST · real card", color = Bg2, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Amber).padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(4.dp))
        }
        Text("${m.name.uppercase()}  ·  AMOUNT DUE", color = OnDarkSoft, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Text(money(p.chargeCents), color = OnDark, fontSize = 52.sp, fontWeight = FontWeight.Black)
        Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize(0.9f)) {
                for (k in 0..2) {
                    val p = (ring + k / 3f) % 1f
                    drawCircle(Teal.copy(alpha = (1f - p) * 0.55f), radius = size.minDimension / 2 * (0.35f + 0.65f * p), style = Stroke(3.dp.toPx()))
                }
                drawCircle(Brush.radialGradient(listOf(Blue.copy(alpha = 0.6f), Blue.copy(alpha = 0.15f))), radius = size.minDimension * 0.2f)
            }
            ContactlessIcon(Color.White, Modifier.size(46.dp))
        }
        Text(p.message, color = OnDark, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Contactless, chip or swipe", color = OnDarkSoft, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Text("Cancel", color = OnDarkSoft, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onCancel).padding(horizontal = 18.dp, vertical = 8.dp))
    }
}

@Composable
private fun StartingScreen(m: LaundryMachineConfig) {
    val t = rememberInfiniteTransition(label = "start")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White.copy(alpha = 0.08f), style = Stroke(8.dp.toPx()))
                rotate(spin) { drawArc(Brush.sweepGradient(listOf(Color.Transparent, Teal)), 0f, 300f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round)) }
            }
            MachineDrawing(m.kind, running = true, modifier = Modifier.width(110.dp).aspectRatio(0.82f))
        }
        Spacer(Modifier.height(14.dp))
        Text("Payment approved", color = Teal, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("Sending credit to machine ${m.machineNo}…", color = OnDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StartedScreen(m: LaundryMachineConfig) {
    var t by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { val s0 = withFrameMillis { it }; while (true) { t = (withFrameMillis { it } - s0) / 1000f } }
    val draw = (t / 0.6f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Teal.copy(alpha = 0.18f))
                drawArc(Teal, -90f, 360f * draw, false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                val p = Path().apply { moveTo(size.width * 0.3f, size.height * 0.52f); lineTo(size.width * 0.45f, size.height * 0.66f); lineTo(size.width * 0.72f, size.height * 0.38f) }
                if (draw >= 1f) drawPath(p, Color.White, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("You're all set!", color = OnDark, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Press ", color = OnDarkSoft, fontSize = 18.sp)
            Text("START", color = Teal, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(" on machine", color = OnDarkSoft, fontSize = 18.sp)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(Blue), contentAlignment = Alignment.Center) {
            Text("${m.machineNo}", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(10.dp))
        Text("Thank you", color = OnDarkSoft, fontSize = 13.sp)
    }
}

@Composable
private fun MessageScreen(color: Color, title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text("!", color = color, fontSize = 44.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = OnDark, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = OnDarkSoft, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
    }
}
