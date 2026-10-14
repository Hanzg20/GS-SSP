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
    onScan: () -> Unit = {},
    onVip: () -> Unit = {},
    onClearPending: () -> Unit = {},
    onBuyVipDemo: (LaundryViewModel.DemoPlan) -> Unit = {},
    onCloseVip: () -> Unit = {},
    onTemp: (LaundryMachineConfig.Option) -> Unit = {},
    onAddon: (LaundryMachineConfig.Option) -> Unit = {},
    onDry: (LaundryMachineConfig.DryTime) -> Unit = {},
    onPayCard: () -> Unit = onPay,
    onPayVip: () -> Unit = {},
    onVipBack: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Bg1, Bg2)))) {
        // Soft glow behind the content, as on the renders.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Blue.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.9f, 0f), radius = size.width * 0.8f))
            drawRect(Brush.radialGradient(listOf(Teal.copy(alpha = 0.10f), Color.Transparent), center = Offset(0f, size.height), radius = size.width * 0.8f))
        }
        AnimatedContent(targetState = s.screen, contentKey = { it::class }, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) }, label = "laundry") { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(s, onSelect, onTechTrigger, onScan, onVip, onClearPending)
                is Screen.Confirm -> ConfirmScreen(screen.order, s, onBack, onPayCard, onPayVip, onScan, onClearPending, onTemp, onAddon, onDry)
                is Screen.VipPay -> VipPayScreen(screen, onScan, onVipBack)
                is Screen.Working -> StartingScreen(screen.machine, title = "One moment", line = screen.message)
                Screen.VipDemo -> VipDemoScreen(onBuyVipDemo, onCloseVip)
                is Screen.VipDemoPaying -> VipDemoPayingScreen(screen.plan)
                is Screen.VipDemoDone -> VipDemoDoneScreen(screen.plan, onCloseVip)
                is Screen.Paying -> PayingScreen(screen, onCancelPayment)
                is Screen.Starting -> StartingScreen(screen.machine)
                is Screen.Started -> StartedScreen(screen.machine, screen.summary)
                is Screen.Declined -> MessageScreen(Rose, "Payment not completed", screen.message)
                is Screen.Unavailable -> MessageScreen(Amber, "${screen.machine.name} is unavailable", "Nothing was charged. Please choose another machine or ask the attendant.")
                is Screen.StartFailed -> MessageScreen(
                    Rose, "Machine ${screen.machine.machineNo} did not start",
                    if (screen.paid) "Your payment is recorded. Please contact the attendant — do not pay again." else "Nothing was charged.",
                )
            }
        }
        // Demo mode carries no badge (customer demos look like the real thing; the technician
        // panel says it). A technician's armed card test does: real money on the next sale.
        if (s.testArmedCents != null) {
            Text(
                "CARD TEST ARMED", color = Bg2, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp).clip(RoundedCornerShape(6.dp)).background(Amber.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- home: the machine grid

@Composable
private fun HomeScreen(
    s: LaundryViewModel.UiState,
    onSelect: (LaundryMachineConfig) -> Unit,
    onTechTrigger: () -> Unit,
    onScan: () -> Unit,
    onVip: () -> Unit,
    onClearPending: () -> Unit,
) {
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
                // The subtitle line also carries a scanned card / coupon, so the grid never moves.
                SubtitleLine(s, onClearPending)
            }
            HeaderButton("Scan", Teal, onClick = onScan) { ScanIcon(Teal, Modifier.size(14.dp)) }
            Spacer(Modifier.width(6.dp))
            HeaderButton("VIP", Amber, onClick = onVip) { CrownIcon(Amber, Modifier.size(14.dp)) }
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
                            val minsLeft = s.endsAt[m.machineNo]?.let { ((it - now + 59_999) / 60_000).toInt() }?.takeIf { it > 0 }
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
private fun SubtitleLine(s: LaundryViewModel.UiState, onClearPending: () -> Unit) {
    val p = s.pending
    when {
        s.notice != null -> Text(s.notice, color = Amber, fontSize = 12.sp, maxLines = 1)
        p is LaundryViewModel.Pending.Vip -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VIP ••${p.memberCode.takeLast(4)} · ${money(p.balanceCents)} — choose a machine", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("  ✕", color = OnDarkSoft, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onClearPending))
        }
        p is LaundryViewModel.Pending.Coupon -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Coupon ${couponLabel(p)} — choose a machine", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("  ✕", color = OnDarkSoft, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onClearPending))
        }
        else -> Text("Choose your machine", color = OnDarkSoft, fontSize = 12.sp)
    }
}

private fun couponLabel(c: LaundryViewModel.Pending.Coupon) = when (c.type) {
    "PERCENT_OFF" -> "${c.value}% off"
    "FIXED_OFF" -> "${money(c.value)} off"
    "FREE_WASH" -> "free cycle"
    else -> c.code
}

@Composable
private fun HeaderButton(text: String, color: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(5.dp))
        Text(text, color = OnDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** A QR-code mark: three finder squares and a few modules. */
@Composable
private fun ScanIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 7f
        val sw = Stroke(u * 0.8f)
        for ((x, y) in listOf(0f to 0f, 4f to 0f, 0f to 4f)) {
            drawRect(color, topLeft = Offset(x * u + u * 0.4f, y * u + u * 0.4f), size = Size(u * 2.2f, u * 2.2f), style = sw)
        }
        for ((x, y) in listOf(4.5f to 4.5f, 6f to 4.5f, 4.5f to 6f, 6f to 6f)) drawRect(color, topLeft = Offset(x * u - u * 0.4f, y * u - u * 0.4f), size = Size(u * 0.9f, u * 0.9f))
    }
}

@Composable
private fun CrownIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val path = Path().apply {
            moveTo(w * 0.05f, h * 0.8f); lineTo(w * 0.05f, h * 0.3f); lineTo(w * 0.3f, h * 0.55f); lineTo(w * 0.5f, h * 0.15f)
            lineTo(w * 0.7f, h * 0.55f); lineTo(w * 0.95f, h * 0.3f); lineTo(w * 0.95f, h * 0.8f); close()
        }
        drawPath(path, color)
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
        Text(
            kindLabel(m) + (m.sizeLb?.let { " · $it lb" } ?: ""),
            color = if (busy) Color.White.copy(alpha = 0.85f) else InkSoft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
        )
        val dry = m.dryTimes.firstOrNull()
        Text(
            when {
                busy && minsLeft != null -> "~$minsLeft min"
                busy -> "Running"
                dry != null -> money(dry.priceCents).removeSuffix(".00") + "/${dry.minutes}m"
                else -> money(m.fromCents)
            },
            color = if (busy) Color.White else Ink, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1
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
private fun ConfirmScreen(
    order: LaundryMachineConfig.Order,
    s: LaundryViewModel.UiState,
    onBack: () -> Unit,
    onPayCard: () -> Unit,
    onPayVip: () -> Unit,
    onScan: () -> Unit,
    onClearPending: () -> Unit,
    onTemp: (LaundryMachineConfig.Option) -> Unit,
    onAddon: (LaundryMachineConfig.Option) -> Unit,
    onDry: (LaundryMachineConfig.DryTime) -> Unit,
) {
    val m = order.machine
    val pending = s.pending
    val vip = pending as? LaundryViewModel.Pending.Vip
    val price = s.priceFor(order)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.weight(1f)) {
            // Left: the machine, its number and price
            Column(Modifier.width(132.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.TopStart) {
                    MachineDrawing(m.kind, running = false, modifier = Modifier.padding(top = 10.dp, start = 10.dp).width(108.dp).aspectRatio(0.82f))
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                        Text("${m.machineNo}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(kindLabel(m).uppercase() + (m.sizeLb?.let { " · $it LB" } ?: ""), color = Teal, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                Text("TOTAL", color = OnDarkSoft, fontSize = 11.sp, letterSpacing = 1.5.sp)
                Text(money(price), color = OnDark, fontSize = 34.sp, fontWeight = FontWeight.Black, maxLines = 1)
                if (price != order.totalCents) {
                    Text(
                        money(order.totalCents), color = OnDarkSoft, fontSize = 14.sp,
                        style = androidx.compose.ui.text.TextStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // Right: what the customer chooses
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.name, color = OnDark, fontSize = 22.sp, fontWeight = FontWeight.Black)
                if (m.temps.isNotEmpty()) {
                    Label("Water temperature")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.temps.forEach { t -> OptionChip(t.label, addLabel(t.addCents), order.temp == t, Modifier.weight(1f)) { onTemp(t) } }
                    }
                }
                if (m.addons.isNotEmpty()) {
                    Label("Extras")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.addons.forEach { a -> OptionChip(a.label, addLabel(a.addCents), a in order.addons, Modifier.weight(1f), toggle = true) { onAddon(a) } }
                    }
                }
                if (m.dryTimes.isNotEmpty()) {
                    Label("Drying time")
                    m.dryTimes.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { d -> OptionChip("${d.minutes} min", money(d.priceCents), order.dry == d, Modifier.weight(1f)) { onDry(d) } }
                            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (m.temps.isEmpty() && m.addons.isEmpty() && m.dryTimes.isEmpty()) {
                    m.durationMin?.let { Text("About $it minutes", color = OnDarkSoft, fontSize = 13.sp) }
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        // How to pay: two equal choices.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PayButton(
                Modifier.weight(1f), Brush.horizontalGradient(listOf(Teal, TealDeep)), onPayCard,
                icon = { CardIcon(Color.White, Modifier.size(width = 22.dp, height = 16.dp)) },
                title = if (price <= 0) "Use coupon" else "Card ${money(price)}",
                sub = if (price <= 0) "Covered by your coupon" else "Tap · Insert · Swipe",
            )
            PayButton(
                Modifier.weight(1f), Brush.horizontalGradient(listOf(Color(0xFFF7C35C), Color(0xFFD9962B))), onPayVip,
                icon = { CrownIcon(Ink, Modifier.size(18.dp)) },
                title = if (vip != null) "VIP ${money(s.vipPrice(order, vip))}" else "VIP card",
                sub = when {
                    vip != null && vip.discountPct > 0 -> "••${vip.memberCode.takeLast(4)} · ${vip.discountPct}% member off"
                    vip != null -> "••${vip.memberCode.takeLast(4)} · ${money(vip.balanceCents)}"
                    else -> "Scan your member QR"
                },
                dark = true,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", color = OnDarkSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onBack).padding(horizontal = 10.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1f))
            when (pending) {
                null -> Row(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onScan).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScanIcon(Teal, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Have a coupon? Scan", color = Teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                else -> Text(
                    (if (pending is LaundryViewModel.Pending.Coupon) "Coupon ${couponLabel(pending)}" else "VIP ••${vip?.memberCode?.takeLast(4)}") + " · Remove",
                    color = OnDarkSoft, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClearPending).padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PayButton(modifier: Modifier, brush: Brush, onClick: () -> Unit, icon: @Composable () -> Unit, title: String, sub: String, dark: Boolean = false) {
    Row(
        modifier.height(62.dp).shadow(8.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(brush)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = if (dark) Ink else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(sub, color = if (dark) Ink.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** A bank card outline with a chip. */
@Composable
private fun CardIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        drawRoundRect(color, cornerRadius = CornerRadius(size.height * 0.18f), style = Stroke(size.height * 0.11f))
        drawRoundRect(color, topLeft = Offset(size.width * 0.14f, size.height * 0.32f), size = Size(size.width * 0.22f, size.height * 0.3f), cornerRadius = CornerRadius(size.height * 0.05f))
    }
}

@Composable
private fun VipPayScreen(v: Screen.VipPay, onScan: () -> Unit, onBack: () -> Unit) {
    val t = rememberInfiniteTransition(label = "vipqr")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "sweep")
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CrownIcon(Amber, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Text("VIP  ·  ${v.order.machine.name.uppercase()}", color = OnDarkSoft, fontSize = 12.sp, letterSpacing = 1.5.sp)
        }
        Text(money(v.order.totalCents), color = OnDark, fontSize = 44.sp, fontWeight = FontWeight.Black)
        Text("Members save up to 15%", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        // The member's phone pass, with a scan line moving over its QR.
        Box(Modifier.weight(1f).aspectRatio(0.75f).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxHeight().aspectRatio(0.56f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0B1220))
                    .border(2.dp, Amber.copy(alpha = 0.8f), RoundedCornerShape(18.dp)).padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CrownIcon(Amber, Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
                        Text("VIP", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                        ScanIcon(Ink, Modifier.fillMaxSize(0.8f))
                        Canvas(Modifier.fillMaxSize()) {
                            val y = size.height * sweep
                            drawLine(Teal, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
                        }
                    }
                }
            }
        }
        Text(v.message, color = OnDark, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).border(1.5.dp, OnDarkSoft.copy(alpha = 0.5f), RoundedCornerShape(24.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Text("Back", color = OnDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Row(
                Modifier.weight(1.6f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Amber.copy(alpha = 0.16f))
                    .border(1.5.dp, Amber, RoundedCornerShape(24.dp)).clickable(onClick = onScan),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                ScanIcon(Amber, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                Text("Scan member QR", color = OnDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun addLabel(cents: Int) = if (cents == 0) "Included" else "+" + money(cents)

@Composable
private fun Label(text: String) = Text(text.uppercase(), color = OnDarkSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)

/** One choice: [selected] filled teal; a toggle shows a check. */
@Composable
private fun OptionChip(title: String, sub: String, selected: Boolean, modifier: Modifier, toggle: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.height(52.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Teal.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(if (selected) 2.dp else 1.dp, if (selected) Teal else Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text((if (toggle && selected) "✓ " else "") + title, color = OnDark, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(sub, color = if (selected) Teal else OnDarkSoft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
private fun StartingScreen(m: LaundryMachineConfig, title: String = "Payment approved", line: String = "Sending credit to machine ${m.machineNo}…") {
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
        Text(title, color = Teal, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(line, color = OnDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StartedScreen(m: LaundryMachineConfig, summary: String) {
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
        if (summary.isNotBlank()) {
            Text("Select on the machine:", color = OnDarkSoft, fontSize = 13.sp)
            Text(summary, color = Teal, fontSize = 18.sp, fontWeight = FontWeight.Black)
        } else Text("Thank you", color = OnDarkSoft, fontSize = 13.sp)
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


// ---------------------------------------------------------------- demo VIP page

@Composable
private fun VipDemoScreen(onBuy: (LaundryViewModel.DemoPlan) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CrownIcon(Amber, Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("VIP MEMBERSHIP", color = OnDark, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Text("Load once, wash and dry with a scan", color = OnDarkSoft, fontSize = 12.sp)
            }
            Text("Close", color = OnDarkSoft, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LaundryViewModel.DEMO_PLANS.forEachIndexed { i, plan ->
                val best = i == LaundryViewModel.DEMO_PLANS.lastIndex
                Column(
                    Modifier.weight(1f).fillMaxHeight().shadow(if (best) 10.dp else 4.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                        .background(if (best) Brush.verticalGradient(listOf(Color(0xFFFFD983), Amber)) else Brush.verticalGradient(listOf(Color.White, Card)))
                        .clickable { onBuy(plan) }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (best) Text("BEST VALUE", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text("PAY", color = InkSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(money(plan.payCents).removeSuffix(".00"), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text("GET", color = InkSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(money(plan.getCents).removeSuffix(".00"), color = if (best) Ink else TealDeep, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("+${(plan.getCents - plan.payCents) * 100 / plan.payCents}% bonus", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Your card is a QR code on your phone. Scan it here to pay for any machine.", color = OnDarkSoft, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun VipDemoPayingScreen(plan: LaundryViewModel.DemoPlan) {
    val t = rememberInfiniteTransition(label = "vippay")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ring")
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("VIP CARD  ·  AMOUNT DUE", color = OnDarkSoft, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Text(money(plan.payCents), color = OnDark, fontSize = 52.sp, fontWeight = FontWeight.Black)
        Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize(0.9f)) {
                for (k in 0..2) {
                    val p = (ring + k / 3f) % 1f
                    drawCircle(Amber.copy(alpha = (1f - p) * 0.55f), radius = size.minDimension / 2 * (0.35f + 0.65f * p), style = Stroke(3.dp.toPx()))
                }
                drawCircle(Brush.radialGradient(listOf(Amber.copy(alpha = 0.6f), Amber.copy(alpha = 0.15f))), radius = size.minDimension * 0.2f)
            }
            ContactlessIcon(Color.White, Modifier.size(46.dp))
        }
        Text("Tap, insert or swipe your card", color = OnDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("You get ${money(plan.getCents)} on your VIP card", color = OnDarkSoft, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun VipDemoDoneScreen(plan: LaundryViewModel.DemoPlan, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp).clickable(onClick = onClose), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // The pass, as the customer gets it on their phone.
        Box(
            Modifier.width(260.dp).height(150.dp).shadow(12.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF111827)))).border(1.dp, Amber.copy(alpha = 0.7f), RoundedCornerShape(18.dp)).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CrownIcon(Amber, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("VIP MEMBER", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            }
            Column(Modifier.align(Alignment.BottomStart)) {
                Text("BALANCE", color = OnDarkSoft, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(money(plan.getCents), color = OnDark, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            Box(Modifier.align(Alignment.BottomEnd).size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                ScanIcon(Ink, Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Welcome to VIP!", color = OnDark, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text("${money(plan.getCents)} loaded — scan your card to pay for any machine", color = OnDarkSoft, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
