package com.goldsky.ssp.feature.timer

import com.goldsky.ssp.payment.hardware.DeclineReason
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import com.goldsky.ssp.core.ui.R as UiR
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.feature.timer.TimerViewModel.Screen
import com.goldsky.ssp.payment.TestSale

// Customer screens: white page, near-black text, solid colour fills -- the vacuum stands
// outdoors, and the old navy + amber look washed out in full sun (owner, 2026-10-26).
// Amber is a fill under Ink text only; amber TEXT on white uses AmberText.
private val Bg = Color.White
private val Surface = Color(0xFFEEF1F5)
private val SurfaceHi = Color(0xFFC3CBD6)
private val Ink = Color(0xFF0B0F17)
private val Amber = Color(0xFFFFB800)
private val AmberText = Color(0xFF9A4D00)
private val Emerald = Color(0xFF047857)
private val Coral = Color(0xFFC8102E)
private val TextHi = Ink
private val TextLo = Color(0xFF3D4757)
// Each unit's own colour on the two-unit home, dark enough for white text in full sun.
private val BayOrange = Color(0xFFD35F00)
private val BayBlue = Color(0xFF1D4ED8)
// Technician pages (staff only, indoors) keep the dark kiosk palette of .cursorrules.
private val TechBg = Color(0xFF121824)
private val TechSurface = Color(0xFF1B2436)
private val TechSurfaceHi = Color(0xFF243049)
private val TechText = Color(0xFFE6EDF7)
private val TechTextLo = Color(0xFF8A97AD)

@Composable
fun TimerApp(
    s: TimerViewModel.UiState,
    version: String,
    onSelect: (TimerPackage, Bay?) -> Unit,
    onCancelPayment: () -> Unit,
    onTechTrigger: () -> Unit,
    onScan: () -> Unit = {},
    onClearPending: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onCancelConfirm: () -> Unit = {},
    onChoiceCard: () -> Unit = {},
    onChoiceScan: () -> Unit = {},
    onChoiceCancel: () -> Unit = {},
    onScanBack: () -> Unit = {},
) {
    // A scanned VIP code / coupon is waiting: the price buttons pulse.
    CompositionLocalProvider(LocalPendingPulse provides (s.pending != null && s.screen is Screen.Home)) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        AnimatedContent(
            targetState = s.screen,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.Home ->
                    if (s.dual) DualHomeScreen(s, version, onSelect, onTechTrigger, onScan)
                    else HomeScreen(s, version, { onSelect(it, null) }, onTechTrigger, onScan)
                is Screen.Confirm -> ConfirmScreen(screen, onConfirm, onCancelConfirm)
                is Screen.ChoosePay -> ChoosePayScreen(screen, onChoiceCard, onChoiceScan, onChoiceCancel)
                is Screen.ScanToPay -> ScanToPayScreen(screen, onScanBack)
                is Screen.Working -> WorkingScreen(screen.message)
                is Screen.Paying -> PayingScreen(screen, s.demoMode, onCancelPayment)
                is Screen.BayStarted -> ResultScreen(
                    Emerald, "✓",
                    if (screen.extended) "${screen.pkg.name} ${screen.bay.number}: time added" else "${screen.pkg.name} ${screen.bay.number} is on",
                    "+${formatDuration(screen.pkg.durationSec)}",
                )
                is Screen.Running -> RunningScreen(screen, s.now)
                is Screen.Finished -> ResultScreen(Emerald, "✓", "Time's up", "")
                is Screen.Declined -> when (screen.reason) {
                    DeclineReason.CANCELLED -> ResultScreen(TextLo, "✕", "Payment cancelled", "No charge was made.")
                    DeclineReason.UNAVAILABLE -> ResultScreen(Coral, "!", "Card payment unavailable", "Please contact the attendant.")
                    DeclineReason.DECLINED -> ResultScreen(Coral, "✕", "Payment not completed", "No charge was made.")
                }
                is Screen.StartFailed -> ResultScreen(
                    Coral, "!", "Machine could not start",
                    when (screen.refunded) {
                        null -> "Reversing your payment…\nIf the card reader asks, present your card again."
                        true -> "Payment reversed. No charge was made."
                        false -> "Please contact the attendant.\nYour payment will be refunded or compensated."
                    },
                )
            }
        }
        if (s.screen is Screen.Home) {
            Column(Modifier.align(Alignment.TopCenter).padding(top = 6.dp, start = 12.dp, end = 12.dp)) {
                s.pending?.let { PendingBanner(it, onClearPending) }
                s.notice?.let {
                    Text(
                        it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            .background(Coral.copy(alpha = 0.92f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        val testArmed by TestSale.armed.collectAsState()
        testArmed?.let {
            Text(
                "TEST MODE · next card sale ${TestSale.format(it.cents)}", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp)
                    .background(Amber.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 1.dp),
            )
        }
        if (s.demoMode) {
            Text(
                "DEMO", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
                    .background(Amber, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
    }
}

// ---- Home ---------------------------------------------------------------

/**
 * Merchant greeting (CMP "Kiosk Marquee Announcement") as a ticker that
 * always scrolls right-to-left. Modifier.basicMarquee only moves when the
 * text overflows, and a short greeting fits the 480px screen.
 */
@Composable
private fun TickerText(text: String, color: Color, fontSize: TextUnit) {
    val style = TextStyle(color = color, fontSize = fontSize, fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val textWidth = remember(text, style) { measurer.measure(text, style, maxLines = 1, softWrap = false).size.width }
    val speedPxPerSec = with(LocalDensity.current) { 70.dp.toPx() }
    var containerWidth by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }
    LaunchedEffect(text, textWidth, containerWidth) {
        if (containerWidth == 0) return@LaunchedEffect
        val span = (containerWidth + textWidth).toFloat()
        while (true) {
            offset.snapTo(0f)
            offset.animateTo(span, tween((span / speedPxPerSec * 1000).toInt(), easing = LinearEasing))
        }
    }
    Text(
        text, style = style, maxLines = 1, softWrap = false,
        modifier = Modifier.fillMaxWidth().onSizeChanged { containerWidth = it.width }.clipToBounds().layout { m, c ->
            val p = m.measure(c.copy(minWidth = 0, maxWidth = Constraints.Infinity))
            layout(c.maxWidth, p.height) { p.placeRelative((c.maxWidth - offset.value).toInt(), 0) }
        },
    )
}

/**
 * Brand name over the announcement ticker, with the health dot. When only one of
 * the two is filled in, it takes the height of both (customer request, 2026-10-21;
 * same as Wash's BrandingManager.applyHeader): the name shrinks only if it can't fit.
 */
@Composable
private fun HomeHeader(s: TimerViewModel.UiState, gap: androidx.compose.ui.unit.Dp) {
    val ticker = s.subtitle?.takeIf { it.isNotBlank() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            when {
                ticker == null -> FitText(s.title, maxSp = 36, minSp = 18)
                !s.titleSet -> TickerText(ticker, color = TextHi, fontSize = 36.sp)
                else -> Text(s.title, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (s.healthy) Emerald else Coral))
    }
    if (ticker != null && s.titleSet) {
        Spacer(Modifier.height(gap))
        TickerText(ticker, color = TextHi, fontSize = 16.sp)
    }
}

/** One bold line at [maxSp], or smaller (not under [minSp]) until it fits the width. */
@Composable
private fun FitText(text: String, maxSp: Int, minSp: Int) {
    val measurer = rememberTextMeasurer()
    var width by remember { mutableIntStateOf(0) }
    val size = remember(text, width) {
        if (width == 0) maxSp
        else (maxSp downTo minSp).firstOrNull { sp ->
            measurer.measure(text, TextStyle(fontSize = sp.sp, fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false).size.width <= width
        } ?: minSp
    }
    Text(
        text, color = TextHi, fontSize = size.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
        modifier = Modifier.fillMaxWidth().onSizeChanged { width = it.width },
    )
}

@Composable
private fun HomeScreen(
    s: TimerViewModel.UiState,
    version: String,
    onSelect: (TimerPackage) -> Unit,
    onTechTrigger: () -> Unit,
    onScan: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        HomeHeader(s, gap = 6.dp)

        Spacer(Modifier.height(14.dp))
        val productName = s.packages.map { it.name }.distinct().singleOrNull() ?: "Self-Service"
        Text(productName, color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
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
                    Text("Temporarily out of service", color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScanButton(onScan)
            Spacer(Modifier.weight(1f))
            TripleTapText(version, onTechTrigger)
        }
    }
}

/**
 * Dual-bay Home: one column per numbered unit, each with its own status and
 * the same packages. Buying for a running unit adds time.
 */
@Composable
private fun DualHomeScreen(
    s: TimerViewModel.UiState,
    version: String,
    onSelect: (TimerPackage, Bay?) -> Unit,
    onTechTrigger: () -> Unit,
    onScan: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        HomeHeader(s, gap = 4.dp)
        Spacer(Modifier.height(8.dp))
        // No "Choose the number shown on your vacuum" line (owner, 2026-10-25): the big unit
        // titles over each column say it.
        val productName = s.packages.map { it.name }.distinct().singleOrNull() ?: "Self-Service"

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (s.packages.isEmpty()) {
                Text("Loading…", color = TextLo, modifier = Modifier.align(Alignment.Center))
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Unit 2 stands to the left of the terminal, unit 1 to the right (owner, 2026-10-26).
                    listOf(Bay.TWO, Bay.ONE).forEach { bay ->
                        BayColumn(bay, productName, s.bays[bay], s.now, s.packages, enabled = !s.locked, modifier = Modifier.weight(1f).fillMaxHeight()) {
                            onSelect(it, bay)
                        }
                    }
                }
            }
            if (s.locked) {
                Column(
                    Modifier.matchParentSize().background(Bg.copy(alpha = 0.92f), RoundedCornerShape(18.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text("Temporarily out of service", color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScanButton(onScan)
            Spacer(Modifier.weight(1f))
            TripleTapText(version, onTechTrigger)
        }
    }
}

@Composable
private fun BayColumn(
    bay: Bay,
    productName: String,
    run: TimerViewModel.BayRun?,
    now: Long,
    packages: List<TimerPackage>,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: (TimerPackage) -> Unit,
) {
    val remaining = run?.remaining(now) ?: 0L
    val running = run != null && remaining > 0
    val theme = if (bay == Bay.TWO) BayOrange else BayBlue
    // Running: a black frame, amber in the last 30 s.
    val frame = when {
        !running -> theme
        remaining <= 30_000 -> Amber
        else -> Ink
    }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(theme)
            .border(4.dp, frame, RoundedCornerShape(16.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BayTitle(bay, productName)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BayRing(running, remaining, run?.totalMs ?: 0L, Color.White)
        }
        packages.take(3).forEach { pkg ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .pendingPulse()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (enabled) Color.White else Color.White.copy(alpha = 0.35f))
                    .clickable(enabled = enabled) { onSelect(pkg) }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatPrice(pkg.priceCents), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    (if (running) "+" else "") + formatDuration(pkg.durationSec),
                    color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The unit's name on its own colour, with an arrow pointing out to that vacuum. */
@Composable
private fun BayTitle(bay: Bay, productName: String) {
    val left = bay == Bay.TWO
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (left) BayArrow(left = true)
        Text(
            bay.title(productName),
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (!left) BayArrow(left = false)
    }
}

@Composable
private fun BayArrow(left: Boolean) {
    Canvas(Modifier.size(18.dp, 20.dp)) {
        val w = size.width
        val h = size.height
        val head = w * 0.5f
        val path = Path().apply {
            // Drawn pointing right, mirrored for the left one.
            fun x(v: Float) = if (left) w - v else v
            moveTo(x(0f), h * 0.32f)
            lineTo(x(w - head), h * 0.32f)
            lineTo(x(w - head), 0f)
            lineTo(x(w), h / 2)
            lineTo(x(w - head), h)
            lineTo(x(w - head), h * 0.68f)
            lineTo(x(0f), h * 0.68f)
            close()
        }
        drawPath(path, Color.White)
    }
}

/**
 * A unit's countdown: ring draining with the remaining time, clock in the
 * middle -- the single-unit RunningScreen's look, sized to fit a column.
 * Idle: an empty track with "Available".
 */
@Composable
private fun BayRing(running: Boolean, remaining: Long, totalMs: Long, color: Color) {
    val fraction = if (!running || totalMs <= 0) 0f else (remaining.toFloat() / totalMs).coerceIn(0f, 1f)
    val animated by animateFloatAsState(fraction, tween(250, easing = LinearEasing), label = "bayRing")
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val size = minOf(maxWidth, maxHeight, 150.dp)
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = 11.dp.toPx()
                drawArc(Color.Black.copy(alpha = 0.22f), 0f, 360f, false, style = Stroke(stroke))
                if (running) drawArc(color, -90f, 360f * animated, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // The vacuum station inside the ring: still when free, air streaming in while it runs.
            if (running) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VacuumDrawing(true, color, Modifier.size(size * 0.42f))
                    Text(formatClock(remaining), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VacuumDrawing(false, color, Modifier.size(size * 0.62f))
                    Text("Available", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
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
            .pendingPulse()
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) Amber else SurfaceHi)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(4.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatPrice(pkg.priceCents), color = Ink, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
            Text(formatDuration(pkg.durationSec), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "START", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(Ink, RoundedCornerShape(10.dp)).padding(vertical = 9.dp),
        )
    }
}

/** Hidden technician entry: 3 taps within 2s on the version label (same gesture as wash). */
@Composable
private fun TripleTapText(version: String, onTriggered: () -> Unit) {
    var count by remember { mutableIntStateOf(0) }
    var last by remember { mutableLongStateOf(0L) }
    Text(
        "v$version", color = TextLo, fontSize = 12.sp,
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
        p.bay?.let { bay ->
            BayTag(bay, p.pkg.name)
            Spacer(Modifier.height(6.dp))
        }
        Text(
            (if (p.chargeCents != p.pkg.priceCents) "TEST ${TestSale.format(p.chargeCents)}" else formatPrice(p.pkg.priceCents)) +
                "  ·  ${formatDuration(p.pkg.durationSec)}",
            color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(150.dp).scale(pulse), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Amber.copy(alpha = 0.12f))
                // Contactless symbol: three nested arcs.
                for (i in 0..2) {
                    val r = size.minDimension * (0.16f + i * 0.1f)
                    drawArc(
                        AmberText, startAngle = -45f, sweepAngle = 90f, useCenter = false,
                        topLeft = Offset(center.x - r - size.minDimension * 0.08f, center.y - r),
                        size = Size(r * 2, r * 2), style = Stroke(width = 9f, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(p.message, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        // A real sale can't be cancelled from here: WizarPOS's
        // cancelCurrentTransaction is a no-op (the P3 socket is blocked on the
        // sale), and PAYWizard's own screen -- which has a Cancel -- is in front
        // during payment anyway. Don't show a button that does nothing, nor
        // hints about the reader nobody sees under PAYWizard's screen.
        if (demo) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Cancel", color = TextLo, fontSize = 16.sp)
            }
        }
    }
}

/** "VACUUM 2" on that unit's own colour, at the top of the payment pages. */
@Composable
private fun BayTag(bay: Bay, productName: String) {
    Text(
        bay.title(productName), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.background(if (bay == Bay.TWO) BayOrange else BayBlue, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 2.dp),
    )
}

/**
 * Wash's payment page (dialog_payment_selection): "Pay $2", then card or scan a coupon /
 * VIP code, same words. [ChoosePay.notice] says why the customer is back here.
 */
@Composable
private fun ChoosePayScreen(c: Screen.ChoosePay, onCard: () -> Unit, onScan: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        c.bay?.let { BayTag(it, c.pkg.name); Spacer(Modifier.height(6.dp)) }
        Text(
            stringResource(UiR.string.pay_title, formatPrice(c.pkg.priceCents)) + "  ·  " + formatDuration(c.pkg.durationSec),
            color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
        )
        c.notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Coral, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            if (c.card) PayChoice(stringResource(UiR.string.btn_pay_card), stringResource(UiR.string.btn_pay_card_desc), Amber, Ink, onCard)
            if (c.scan) PayChoice(stringResource(UiR.string.btn_pay_code), stringResource(UiR.string.btn_pay_code_desc), Ink, Color.White, onScan)
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(50.dp), border = BorderStroke(2.dp, Ink)) {
            Text("Cancel", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PayChoice(title: String, sub: String, fill: Color, text: Color, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(fill).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text(sub, color = text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** Behind the scanner's camera page, and what's left when the customer backs out of it. */
@Composable
private fun ScanToPayScreen(sc: Screen.ScanToPay, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        sc.bay?.let { BayTag(it, sc.pkg.name); Spacer(Modifier.height(6.dp)) }
        Text(formatPrice(sc.pkg.priceCents) + "  ·  " + formatDuration(sc.pkg.durationSec), color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        Text("▣", color = Ink, fontSize = 64.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(UiR.string.btn_pay_code_desc), color = TextHi, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(50.dp), border = BorderStroke(2.dp, Ink)) {
            Text("Back", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
        Text("${r.pkg.name.uppercase()} ON", color = if (endingSoon) AmberText else Emerald, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val stroke = 22.dp.toPx()
                drawArc(SurfaceHi, 0f, 360f, false, style = Stroke(stroke))
                drawArc(color, -90f, 360f * animated, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatClock(remaining), color = TextHi, fontSize = 72.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.weight(1f))
        // Only the last 30 s get a line: that's when the customer has to act.
        Text(
            if (endingSoon) "Almost done — finish up!" else "",
            color = AmberText, fontSize = 24.sp, fontWeight = FontWeight.Bold,
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
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(body, color = TextHi, fontSize = 20.sp, textAlign = TextAlign.Center)
        }
    }
}

// ---- Technician ---------------------------------------------------------

@Composable
fun PinDialog(onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(300.dp).background(TechSurface, RoundedCornerShape(16.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("技术员 PIN", color = TechText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                                .background(if (k == "OK") Amber else TechSurfaceHi)
                                .clickable {
                                    wrong = false
                                    when (k) {
                                        "✕" -> if (pin.isEmpty()) onDismiss() else pin = pin.dropLast(1)
                                        "OK" -> if (!onSubmit(pin)) { wrong = true; pin = "" }
                                        else -> if (pin.length < 8) pin += k
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(k, color = if (k == "OK") TechBg else TechText, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
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
    onSettle: () -> Unit,
    outputSettings: OutputSettings,
    onOutputChange: (OutputSettings) -> Unit,
    onTestOutput: () -> Unit,
    holdTest: @Composable () -> Unit,
    dualBay: Boolean = false,
    onDualChange: (Boolean) -> Unit = {},
    baySettings: Map<Bay, OutputSettings> = emptyMap(),
    onBayChange: (Bay, OutputSettings) -> Unit = { _, _ -> },
    onTestBay: (Bay) -> Unit = {},
    switchTarget: String? = null,
    onSwitchApp: () -> Unit = {},
    onPaymentTests: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(TechBg)) {
        Row(
            Modifier.fillMaxWidth().background(TechSurface).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("技术员模式", color = TechText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("演示模式", color = TechTextLo, fontSize = 13.sp)
            Switch(checked = demoMode, onCheckedChange = onDemoChange, modifier = Modifier.padding(horizontal = 6.dp).scale(0.8f))
            switchTarget?.let { TextButton(onClick = onSwitchApp) { Text("切换到 $it", color = Amber) } }
            TextButton(onClick = onPaymentTests) { Text("支付用例", color = Amber) }
            TextButton(onClick = onExit) { Text("返回", color = Amber) }
        }
        // Everything below the bar scrolls as one page: on the Q3mini's ~480x480 screen the
        // panels above used to stay put and left the scrolling part a sliver (owner, 2026-10-20).
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text(
            if (demoMode) "演示模式开：客户流程跳过刷卡、不驱动输出、不写交易记录。重启应用自动关闭。"
            else "演示模式关：客户流程真实刷卡、真实驱动输出。",
            color = if (demoMode) Amber else TechTextLo, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        // System check: network (Wi-Fi / SIM / Ethernet), card service, HOME, overlay, clock, screen, volume.
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).background(TechSurface, RoundedCornerShape(10.dp)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("系统检查", color = TechText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("网络、刷卡服务、默认桌面、权限、时间、屏幕、音量，一键去设置", color = TechTextLo, fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { ctx.startActivity(android.content.Intent(ctx, com.goldsky.ssp.ui.SystemCheckActivity::class.java)) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("打开", fontSize = 13.sp, color = TechText) }
        }
        PaymentSelfTestPanel(selfTest, onRunSelfTest, onSettle)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("一拖二（1 号 / 2 号独立计时）", color = TechText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("1 号 PIN1、2 号 PIN2，各自投币脉冲；切换后退出技术员模式时应用自动重启生效", color = TechTextLo, fontSize = 10.sp)
            }
            Switch(checked = dualBay, onCheckedChange = onDualChange, modifier = Modifier.scale(0.8f))
        }
        if (dualBay) {
            Bay.entries.forEach { bay ->
                BayOutputPanel(bay, baySettings[bay] ?: OutputSettings(OutputMode.COIN_PULSES, bay.defaultPort), { onBayChange(bay, it) }) { onTestBay(bay) }
            }
        } else {
            OutputSettingsPanel(outputSettings, onOutputChange, onTestOutput)
            // The output test is a list of its own (pinned countdown): a fixed height inside the
            // page, so the two scrolls don't fight -- it scrolls within, the page around it.
            Box(Modifier.fillMaxWidth().height(420.dp)) { holdTest() }
        }
        }
    }
}

/**
 * How customer sessions drive the machine -- picked on site to match the
 * wiring (relay hold, PIN1 level hold, or coin-acceptor pulses). Saved on
 * the terminal; the next session uses it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputSettingsPanel(s: OutputSettings, onChange: (OutputSettings) -> Unit, onTest: () -> Unit) {
    @Composable
    fun Pick(text: String, selected: Boolean, onClick: () -> Unit) =
        FilterChip(selected = selected, onClick = onClick, label = { Text(text, fontSize = 11.sp) }, modifier = Modifier.padding(end = 4.dp))

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).background(TechSurface, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("输出方式", color = TechText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(
                onClick = onTest,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = TechBg),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            ) { Text("试运行", fontSize = 12.sp) }
        }
        Row { OutputMode.entries.forEach { m -> Pick(m.label, s.mode == m) { onChange(s.copy(mode = m)) } } }
        Text(s.mode.hint, color = TechTextLo, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("端口", color = TechTextLo, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
            listOf(0, 1).forEach { p -> Pick("port $p", s.port == p) { onChange(s.copy(port = p)) } }
            if (s.mode != OutputMode.RELAY_HOLD) {
                Text("voltage", color = TechTextLo, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
                listOf(0, 1).forEach { v -> Pick("$v", s.voltage == v) { onChange(s.copy(voltage = v)) } }
            }
        }
        if (s.mode == OutputMode.COIN_PULSES) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每脉冲", color = TechTextLo, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                listOf(25, 50, 100, 200).forEach { c -> Pick("$" + "%.2f".format(c / 100.0), s.centsPerPulse == c) { onChange(s.copy(centsPerPulse = c)) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("脉宽", color = TechTextLo, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                listOf(50L, 100L, 200L, 500L).forEach { w -> Pick("${w}ms", s.pulseWidthMs == w) { onChange(s.copy(pulseWidthMs = w)) } }
            }
            Text("例：$2 套餐 → ${s.pulsesFor(200)} 个脉冲", color = TechTextLo, fontSize = 10.sp)
        }
    }
}

/** One side of a dual-bay terminal: coin pulses only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BayOutputPanel(bay: Bay, s: OutputSettings, onChange: (OutputSettings) -> Unit, onTest: () -> Unit) {
    @Composable
    fun Pick(text: String, selected: Boolean, onClick: () -> Unit) =
        FilterChip(selected = selected, onClick = onClick, label = { Text(text, fontSize = 11.sp) }, modifier = Modifier.padding(end = 4.dp))

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).background(TechSurface, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${bay.number} 号（默认 PIN${bay.defaultPort + 1}）", color = TechText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(
                onClick = onTest,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = TechBg),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            ) { Text("发 1 个脉冲", fontSize = 12.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("端口", color = TechTextLo, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
            listOf(0 to "PIN1", 1 to "PIN2").forEach { (p, name) -> Pick(name, s.port == p) { onChange(s.copy(port = p)) } }
            Text("voltage", color = TechTextLo, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
            listOf(0, 1).forEach { v -> Pick("$v", s.voltage == v) { onChange(s.copy(voltage = v)) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每脉冲", color = TechTextLo, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
            listOf(25, 50, 100, 200).forEach { c -> Pick("$" + "%.2f".format(c / 100.0), s.centsPerPulse == c) { onChange(s.copy(centsPerPulse = c)) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("脉宽", color = TechTextLo, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
            listOf(50L, 100L, 200L, 500L).forEach { w -> Pick("${w}ms", s.pulseWidthMs == w) { onChange(s.copy(pulseWidthMs = w)) } }
        }
        Text("例：$2 套餐 → ${s.pulsesFor(200)} 个脉冲", color = TechTextLo, fontSize = 10.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSelfTestPanel(s: PaymentSelfTestViewModel.State, onRun: () -> Unit, onSettle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).background(TechSurface, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("支付撤销自检", color = TechText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("真实刷卡 $1.00，成功后立即撤销", color = TechTextLo, fontSize = 11.sp)
            }
            Button(
                onClick = onRun, enabled = !s.running,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = TechBg),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(if (s.running) "进行中…" else "开始", fontSize = 13.sp) }
            Spacer(Modifier.width(6.dp))
            // Closing the batch can't be undone: ask first (Wash's CLOSE BATCH was hit by
            // mistake twice on 255, 2026-10-14).
            var confirmSettle by remember { mutableStateOf(false) }
            if (confirmSettle) {
                AlertDialog(
                    onDismissRequest = { confirmSettle = false },
                    title = { Text("立即结算？") },
                    text = { Text("把上次结算以来的所有刷卡交易送银行结算，不能撤销。（每天 03:30 也会自动结算。）") },
                    confirmButton = { TextButton(onClick = { confirmSettle = false; onSettle() }) { Text("结算") } },
                    dismissButton = { TextButton(onClick = { confirmSettle = false }) { Text("取消") } },
                )
            }
            OutlinedButton(
                onClick = { confirmSettle = true }, enabled = !s.running,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("立即结算", fontSize = 13.sp, color = TechText) }
        }
        val armed by TestSale.armed.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("小额真实测试", color = TechText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("下一笔刷卡按此金额收款，其余完全按真实流程（设备照常运行）", color = TechTextLo, fontSize = 10.sp)
            }
            TestSale.AMOUNTS.forEach { c ->
                FilterChip(
                    selected = armed?.cents == c,
                    onClick = { if (armed?.cents == c) TestSale.disarm() else TestSale.arm(c) },
                    label = { Text(TestSale.format(c), fontSize = 11.sp) },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        s.lines.takeLast(3).forEach { Text(it, color = TechTextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2) }
    }
}

// ---- scanned VIP code / coupon --------------------------------------------

/** Opens the scanner for a coupon or a VIP member code (the WizarPOS scanner shows its own camera page). */
@Composable
private fun ScanButton(onScan: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Amber)
            .clickable(onClick = onScan).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▣", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("  Coupon / VIP code", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

private val LocalPendingPulse = compositionLocalOf { false }

/** Gentle pulse on the price buttons while a scanned VIP code / coupon waits for a choice. */
@Composable
private fun Modifier.pendingPulse(): Modifier {
    if (!LocalPendingPulse.current) return this
    val t = rememberInfiniteTransition(label = "pendingPulse")
    val k by t.animateFloat(1f, 1.05f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "k")
    return this.scale(k)
}

/**
 * Two big lines with a check, like Wash: customers with little English
 * missed the old one-line 13sp banner and thought the scan did nothing
 * (2026-10-07).
 */
@Composable
private fun PendingBanner(p: TimerViewModel.Pending, onClear: () -> Unit) {
    val title = when (p) {
        is TimerViewModel.Pending.Vip -> "VIP ${p.memberCode}  ·  ${formatPrice(p.balanceCents)}"
        is TimerViewModel.Pending.Coupon -> "Coupon ${couponLabel(p)}"
    }
    Row(
        Modifier.fillMaxWidth().background(Amber, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✓", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text("Tap a time below to pay  ↓", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text("✕", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

private fun couponLabel(c: TimerViewModel.Pending.Coupon) = when (c.type) {
    "PERCENT_OFF" -> "${c.value}% OFF"
    "FIXED_OFF" -> "${formatPrice(c.value)} OFF"
    "FREE_WASH" -> "FREE"
    else -> c.code
}

/** Confirm before a VIP balance or a coupon is used (nothing is spent until here). */
@Composable
private fun ConfirmScreen(c: Screen.Confirm, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val vip = c.pending as? TimerViewModel.Pending.Vip
    val coupon = c.pending as? TimerViewModel.Pending.Coupon
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface)
                .border(2.dp, SurfaceHi, RoundedCornerShape(22.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (vip != null) "Pay with your VIP card?" else "Use this coupon?", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            val unit = c.bay?.let { " · ${c.pkg.name} ${it.number}" } ?: ""
            Text("${formatDuration(c.pkg.durationSec)} · ${formatPrice(c.pkg.priceCents)}$unit", color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            if (vip != null) {
                Text(formatPrice(c.pkg.priceCents), color = Ink, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                val after = vip.balanceCents - c.pkg.priceCents
                Text(
                    if (after >= 0) "Balance ${formatPrice(vip.balanceCents)} → ${formatPrice(after)}" else "Balance ${formatPrice(vip.balanceCents)} is not enough",
                    color = if (after >= 0) TextHi else Coral, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
            } else if (coupon != null) {
                Text(couponLabel(coupon), color = Emerald, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(if (c.chargeCents <= 0) "FREE" else "Pay ${formatPrice(c.chargeCents)}", color = Ink, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("Cancel", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onConfirm,
                    enabled = vip == null || vip.balanceCents >= c.pkg.priceCents,
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Confirm", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun WorkingScreen(message: String) {
    Column(
        Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Ink, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(message, color = TextHi, fontSize = 18.sp)
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
