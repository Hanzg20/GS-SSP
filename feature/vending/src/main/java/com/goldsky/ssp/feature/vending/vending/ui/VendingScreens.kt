package com.goldsky.ssp.vending.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.vending.VendingViewModel
import com.goldsky.ssp.vending.logic.PurchaseFlow
import com.goldsky.ssp.vending.logic.VendingState
import com.goldsky.ssp.vending.logic.VendingState.Reason
import com.goldsky.ssp.vending.ui.components.NfcRadar
import com.goldsky.ssp.vending.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Sizes for the screen we're on: the Q3mini is ~320dp square, so below 480dp of
 * height everything tightens (same idea as core/ui's values-h480dp). No small print
 * (owner, 2026-11-03): the smallest text is [body].
 */
private class Dims(c: Boolean) {
    val bar: Dp = if (c) 44.dp else 56.dp
    val pad: Dp = if (c) 8.dp else 12.dp
    val title: TextUnit = if (c) 30.sp else 38.sp
    val headline: TextUnit = if (c) 26.sp else 32.sp
    val body: TextUnit = if (c) 20.sp else 24.sp
    val price: TextUnit = if (c) 60.sp else 76.sp
    val button: Dp = if (c) 64.dp else 76.dp
    val cancel: Dp = if (c) 52.dp else 60.dp
    val buttonText: TextUnit = if (c) 26.sp else 30.sp
    val mark: Dp = if (c) 80.dp else 100.dp
    val gap: Dp = if (c) 12.dp else 18.dp
}

private val LocalDims = compositionLocalOf { Dims(false) }

/** The VIP page's title look: bold, widely spaced capitals. */
private val Spaced = 0.08.em

/**
 * Aegis Vend, the screen on the vending machine's front (Q3mini: ~4", square), in the VIP
 * page's black and gold (owner, 2026-11-03). One message per screen, big bold words, no
 * explanations, one gold button at most, so it reads at a glance next to the machine's
 * own keypad and display. The idle screen follows the purchase flow (see [VendingState]).
 */
@Composable
fun VendingMainContainer(viewModel: VendingViewModel, version: String = "", onVip: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    val french by viewModel.french.collectAsState()
    val flow by viewModel.purchaseFlow.collectAsState()
    val vipBonus by viewModel.vipBonus.collectAsState()
    val s = if (french) VendStrings.FR else VendStrings.EN
    val money = { cents: Int -> VendStrings.money(cents, french) }

    val compact = LocalConfiguration.current.screenHeightDp < 480
    VendingTheme {
        CompositionLocalProvider(LocalDims provides remember(compact) { Dims(compact) }) {
        Column(Modifier.fillMaxSize().background(KioskPage)) {
            if (viewModel.vmcSimulator) {
                // Can't be missed: no vending machine, real card payments.
                Text(
                    "VMC SIMULATOR · TEST",
                    color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(KioskRed).padding(vertical = 2.dp),
                )
            }
            TopBar(s, version, showToggle = state !is VendingState.Unavailable, onToggle = viewModel::toggleLanguage)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(targetState = state, label = "screen") { st ->
                    when (st) {
                        VendingState.Idle -> IdleScreen(s, flow, viewModel::startSession)
                        is VendingState.Authorizing -> AuthorizingScreen(s, st.amountCents?.let(money))
                        is VendingState.SessionOpen -> SessionScreen(s, money(st.limitCents), st.deadline, viewModel::cancel)
                        is VendingState.VendRequestReceived -> PriceScreen(s, st.itemSlot, money(st.amountCents), viewModel::startPayment, viewModel::cancel)
                        is VendingState.Dispensing -> DispensingScreen(s)
                        is VendingState.Success -> SuccessScreen(s, money(st.amountCents))
                        is VendingState.NotCharged -> NotChargedScreen(s, st.reason, money(VendingViewModel.SESSION_LIMIT_CENTS))
                        VendingState.Unavailable -> UnavailableScreen()
                        VendingState.Booting -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator(color = KioskAmber)
                        }
                    }
                }
            }
            if (state == VendingState.Idle) {
                vipBonus?.let { bonus -> VipBanner(if (bonus > 0) s.vipBonus(bonus) else s.vipBanner, onVip) }
            }
        }
        }
    }
}

/** Wash's VIP banner, here: buy or top up a VIP card (VipActivity). Only while the store sells VIP loads. */
@Composable
private fun VipBanner(text: String, onClick: () -> Unit) {
    val d = LocalDims.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = d.pad).height(d.cancel).clip(shape)
            .background(kioskTile(best = true)).border(1.5.dp, KioskAmber, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = KioskAmber, fontSize = d.body, fontWeight = FontWeight.Bold, letterSpacing = Spaced, maxLines = 1)
    }
}

@Composable
private fun TopBar(s: VendStrings, version: String, showToggle: Boolean, onToggle: () -> Unit) {
    val d = LocalDims.current
    Row(
        Modifier.fillMaxWidth().height(d.bar).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The terminal's own brand (owner, 2026-11-02), not the store's: a vending operator's
        // machines carry the payment brand, whatever the store is called in Cael.
        Text("GSPay", color = KioskAmber, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = Spaced)
        if (version.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text("ver $version", color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        if (showToggle) {
            Box(
                Modifier.height(d.bar - 6.dp).clip(RoundedCornerShape(20.dp)).background(KioskCard)
                    .border(1.dp, KioskCardEdge, RoundedCornerShape(20.dp))
                    .clickable(onClick = onToggle).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(s.langToggle, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Centered column every screen shares. */
@Composable
private fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = LocalDims.current.pad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

/** A screen's one big line, bold, spaced capitals like the VIP page's title. */
@Composable
private fun Title(text: String, color: Color = TextPrimary, size: TextUnit = LocalDims.current.title) =
    Text(text, color = color, fontSize = size, fontWeight = FontWeight.Bold, letterSpacing = Spaced,
        textAlign = TextAlign.Center, lineHeight = size * 1.15f)

/** The one line under it, if any. */
@Composable
private fun Line(text: String, color: Color = TextSecondary) {
    val d = LocalDims.current
    Text(text, color = color, fontSize = d.body, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        lineHeight = d.body * 1.2f)
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    val d = LocalDims.current
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(d.button),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = KioskAmber, contentColor = KioskBg)
    ) {
        Text(text, fontSize = d.buttonText, fontWeight = FontWeight.Bold, letterSpacing = Spaced)
    }
}

@Composable
private fun CancelButton(text: String, onClick: () -> Unit) {
    val d = LocalDims.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().height(d.cancel).clip(shape).background(kioskTile())
            .border(1.dp, KioskCardEdge, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = TextPrimary, fontSize = d.body, fontWeight = FontWeight.Bold, letterSpacing = Spaced)
    }
}

/** What the customer does first follows the terminal's purchase flow (technician panel). */
@Composable
private fun IdleScreen(s: VendStrings, flow: PurchaseFlow, onStart: () -> Unit) {
    val d = LocalDims.current
    Screen {
        if (flow == PurchaseFlow.ITEM_FIRST) {
            // No START: the price arrives from the machine (PriceScreen).
            NfcRadar(size = d.mark)
            Spacer(Modifier.height(d.gap))
            Title(s.itemFirstTitle, KioskAmber)
        } else {
            Title(s.startTitle, KioskAmber)
            Spacer(Modifier.height(d.gap * 2))
            BigButton(s.startButton, onStart)
            if (flow == PurchaseFlow.BOTH) {
                Spacer(Modifier.height(d.gap))
                Line(s.orChooseFirst)
            }
        }
    }
}

@Composable
private fun AuthorizingScreen(s: VendStrings, amount: String?) {
    val d = LocalDims.current
    Screen {
        if (amount != null) {
            Text(amount, color = KioskAmber, fontSize = d.price, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(d.gap))
        }
        CircularProgressIndicator(color = KioskAmber, strokeWidth = 5.dp, modifier = Modifier.size(d.mark * 0.7f))
        Spacer(Modifier.height(d.gap))
        Title(s.authorizing, size = d.headline)
    }
}

@Composable
private fun SessionScreen(s: VendStrings, limit: String, deadline: Long, onCancel: () -> Unit) {
    val d = LocalDims.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (now < deadline) { delay(250); now = System.currentTimeMillis() }
    }
    val leftMs = (deadline - now).coerceAtLeast(0)
    val left = ((leftMs + 999) / 1000).toInt()
    val frac = (leftMs.toFloat() / VendingViewModel.SESSION_MS).coerceIn(0f, 1f)
    Screen {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(d.mark)) {
            Canvas(Modifier.fillMaxSize()) {
                val st = 7.dp.toPx()
                val tl = Offset(st / 2, st / 2)
                val sz = Size(size.width - st, size.height - st)
                drawArc(KioskCard, 0f, 360f, false, tl, sz, style = Stroke(st))
                drawArc(if (left > 10) KioskEmerald else KioskAmber, -90f, 360f * frac, false, tl, sz,
                    style = Stroke(st, cap = StrokeCap.Round))
            }
            Text(s.secondsLeft(left), color = TextPrimary, fontSize = d.headline, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(d.gap))
        Title(s.chooseOnMachine, KioskAmber, d.headline)
        Spacer(Modifier.height(6.dp))
        // The hold the card shows, in one short line (it's not the price).
        Line(s.holdNote(limit))
        Spacer(Modifier.height(d.gap))
        CancelButton(s.cancel, onCancel)
    }
}

@Composable
private fun PriceScreen(s: VendStrings, item: String, price: String, onPay: () -> Unit, onCancel: () -> Unit) {
    val d = LocalDims.current
    Screen {
        Line(s.item(item), TextPrimary)
        Text(price, color = KioskAmber, fontSize = d.price, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(d.gap))
        BigButton(s.payButton, onPay)
        Spacer(Modifier.height(10.dp))
        CancelButton(s.cancel, onCancel)
    }
}

@Composable
private fun DispensingScreen(s: VendStrings) {
    val d = LocalDims.current
    Screen {
        CircularProgressIndicator(color = KioskEmerald, strokeWidth = 5.dp, modifier = Modifier.size(d.mark * 0.7f))
        Spacer(Modifier.height(d.gap))
        Title(s.dispensing)
    }
}

@Composable
private fun ResultMark(ok: Boolean) {
    val color = if (ok) KioskEmerald else KioskAmber
    Canvas(Modifier.size(LocalDims.current.mark)) {
        val w = size.width
        drawCircle(color.copy(alpha = 0.18f))
        drawCircle(color, radius = w * 0.36f)
        val st = Stroke(w * 0.08f, cap = StrokeCap.Round)
        if (ok) {
            val path = Path().apply {
                moveTo(w * 0.33f, w * 0.52f); lineTo(w * 0.45f, w * 0.64f); lineTo(w * 0.68f, w * 0.38f)
            }
            drawPath(path, KioskBg, style = st)
        } else {
            // "!" -- nothing taken, not a scolding red cross.
            drawLine(KioskBg, Offset(w / 2, w * 0.32f), Offset(w / 2, w * 0.54f), st.width, StrokeCap.Round)
            drawCircle(KioskBg, radius = st.width * 0.6f, center = Offset(w / 2, w * 0.68f))
        }
    }
}

@Composable
private fun SuccessScreen(s: VendStrings, price: String) {
    val d = LocalDims.current
    Screen {
        ResultMark(true)
        Spacer(Modifier.height(d.gap))
        Title(s.takeItem)
        Spacer(Modifier.height(6.dp))
        Line(s.charged(price))
    }
}

@Composable
private fun NotChargedScreen(s: VendStrings, reason: Reason, limit: String) {
    val d = LocalDims.current
    Screen {
        ResultMark(false)
        Spacer(Modifier.height(d.gap))
        Title(s.reason(reason, limit), size = d.headline)
        Spacer(Modifier.height(6.dp))
        Line(s.notCharged, KioskEmerald)
    }
}

@Composable
private fun UnavailableScreen() {
    val d = LocalDims.current
    // Both languages at once: nobody can act on this screen, so no toggle.
    Screen {
        ResultMark(false)
        Spacer(Modifier.height(d.gap))
        Title(VendStrings.EN.unavailableTitle, size = d.headline)
        Spacer(Modifier.height(d.gap))
        Title(VendStrings.FR.unavailableTitle, size = d.headline)
    }
}
