package com.goldsky.ssp.vending.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
 * height everything tightens (same idea as core/ui's values-h480dp).
 */
private class Dims(c: Boolean) {
    val bar: Dp = if (c) 40.dp else 52.dp
    val pad: Dp = if (c) 6.dp else 8.dp
    val title: TextUnit = if (c) 22.sp else 28.sp
    val headline: TextUnit = if (c) 20.sp else 24.sp
    val body: TextUnit = if (c) 14.sp else 16.sp
    val small: TextUnit = if (c) 11.sp else 13.sp
    val price: TextUnit = if (c) 40.sp else 52.sp
    val button: Dp = if (c) 52.dp else 64.dp
    val cancel: Dp = if (c) 44.dp else 52.dp
    val buttonText: TextUnit = if (c) 20.sp else 24.sp
    val mark: Dp = if (c) 64.dp else 84.dp
    val gap: Dp = if (c) 8.dp else 14.dp
}

private val LocalDims = compositionLocalOf { Dims(false) }

/**
 * Aegis Vend, the screen on the vending machine's front (Q3mini: ~4", square).
 * One message per screen, big type, one amber button at most, so it reads at a
 * glance next to the machine's own keypad and display. Both MDB flows are shown
 * (see [VendingState]): the idle screen leads with card first (START), and a
 * price from the machine (item first) is taken from any idle/result screen.
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
        Surface(modifier = Modifier.fillMaxSize(), color = KioskBg) {
            Column(Modifier.fillMaxSize()) {
                if (viewModel.vmcSimulator) {
                    // Can't be missed: no vending machine, real card payments.
                    Text(
                        "VMC SIMULATOR · TEST ONLY · REAL CARD PAYMENTS",
                        color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(KioskRed).padding(vertical = 3.dp),
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
                            is VendingState.Dispensing -> DispensingScreen(s, st.itemSlot, money(st.amountCents))
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
                    Text(
                        s.cardsAccepted,
                        color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = LocalDims.current.pad)
                    )
                }
            }
        }
        }
    }
}

/** Wash's VIP banner, here: buy or top up a VIP card (VipActivity). Only while the store sells VIP loads. */
@Composable
private fun VipBanner(text: String, onClick: () -> Unit) {
    val d = LocalDims.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(d.cancel * 0.8f).clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, KioskAmber, RoundedCornerShape(12.dp)).background(KioskAmber.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = KioskAmber, fontSize = d.small, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

@Composable
private fun TopBar(s: VendStrings, version: String, showToggle: Boolean, onToggle: () -> Unit) {
    val d = LocalDims.current
    Row(
        Modifier.fillMaxWidth().height(d.bar).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The terminal's own brand (owner, 2026-11-02), not the store's: a vending operator's
        // machines carry the payment brand, whatever the store is called in Cael.
        Text("GSPay", color = KioskAmber, fontSize = d.small, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        if (version.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text("ver $version", color = TextSecondary, fontSize = d.small * 0.85f)
        }
        Spacer(Modifier.weight(1f))
        if (showToggle) {
            // A thumb-sized pill, quiet next to START.
            Box(
                Modifier.height(d.bar - 6.dp).clip(RoundedCornerShape(20.dp))
                    .border(1.dp, TextSecondary.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .clickable(onClick = onToggle).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(s.langToggle, color = TextPrimary, fontSize = d.small, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Centered column every screen shares. */
@Composable
private fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = LocalDims.current.pad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
private fun BigButton(text: String, color: Color, onClick: () -> Unit) {
    val d = LocalDims.current
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(d.button),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = KioskBg)
    ) {
        Text(text, fontSize = d.buttonText, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun CancelButton(text: String, onClick: () -> Unit) {
    val d = LocalDims.current
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(d.cancel),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, TextSecondary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
    ) {
        Text(text, fontSize = d.body, fontWeight = FontWeight.Bold)
    }
}

/** What the customer does first follows the terminal's purchase flow (technician panel). */
@Composable
private fun IdleScreen(s: VendStrings, flow: PurchaseFlow, onStart: () -> Unit) {
    val d = LocalDims.current
    Screen {
        if (flow == PurchaseFlow.ITEM_FIRST) {
            // No START: the price arrives from the machine (PriceScreen).
            Text(s.itemFirstTitle, color = TextPrimary, fontSize = d.headline, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(d.gap))
            StepRow(1, s.step2)
            Spacer(Modifier.height(6.dp))
            StepRow(2, s.itemStep2)
            Spacer(Modifier.height(d.gap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NfcRadar(size = 32.dp)
                Spacer(Modifier.width(6.dp))
                Text(s.priceShowsHere, color = TextSecondary, fontSize = d.body, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(s.startTitle, color = TextPrimary, fontSize = d.title, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(d.gap))
            StepRow(1, s.step1)
            Spacer(Modifier.height(6.dp))
            StepRow(2, s.step2)
            Spacer(Modifier.height(d.gap))
            BigButton(s.startButton, KioskAmber, onStart)
            if (flow == PurchaseFlow.BOTH) {
                Spacer(Modifier.height(6.dp))
                Text(s.orChooseFirst, color = TextSecondary, fontSize = d.small, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun StepRow(n: Int, text: String) {
    val d = LocalDims.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(KioskCard).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(26.dp).background(KioskAmber, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = KioskBg, fontSize = d.body, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextPrimary, fontSize = d.body, fontWeight = FontWeight.SemiBold, lineHeight = d.body * 1.2f)
    }
}

@Composable
private fun AuthorizingScreen(s: VendStrings, amount: String?) {
    val d = LocalDims.current
    Screen {
        if (amount != null) {
            Text(amount, color = TextPrimary, fontSize = d.price, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(d.gap))
        }
        CircularProgressIndicator(color = KioskAmber, strokeWidth = 5.dp, modifier = Modifier.size(d.mark * 0.75f))
        Spacer(Modifier.height(d.gap))
        Text(s.authorizing, color = TextPrimary, fontSize = d.headline, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(s.keepCard, color = TextSecondary, fontSize = d.body, textAlign = TextAlign.Center)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(d.mark)) {
                Canvas(Modifier.fillMaxSize()) {
                    val st = 7.dp.toPx()
                    val tl = Offset(st / 2, st / 2)
                    val sz = Size(size.width - st, size.height - st)
                    drawArc(KioskCard, 0f, 360f, false, tl, sz, style = Stroke(st))
                    drawArc(if (left > 10) KioskEmerald else KioskAmber, -90f, 360f * frac, false, tl, sz,
                        style = Stroke(st, cap = StrokeCap.Round))
                }
                Text(s.secondsLeft(left), color = TextPrimary, fontSize = d.body, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Text(s.approved, color = KioskEmerald, fontSize = d.headline, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(d.gap))
        Text(s.chooseOnMachine, color = TextPrimary, fontSize = d.headline, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center, lineHeight = d.headline * 1.2f)
        Spacer(Modifier.height(d.gap))
        Text(s.holdNote(limit), color = TextSecondary, fontSize = d.small, textAlign = TextAlign.Center, lineHeight = d.small * 1.3f)
        Spacer(Modifier.height(d.gap))
        CancelButton(s.cancel, onCancel)
    }
}

@Composable
private fun PriceScreen(s: VendStrings, item: String, price: String, onPay: () -> Unit, onCancel: () -> Unit) {
    val d = LocalDims.current
    Screen {
        Text(s.item(item), color = TextSecondary, fontSize = d.body, fontWeight = FontWeight.Bold)
        Text(price, color = TextPrimary, fontSize = d.price, fontWeight = FontWeight.Black)
        Row(verticalAlignment = Alignment.CenterVertically) {
            NfcRadar(size = 32.dp)
            Spacer(Modifier.width(6.dp))
            Text(s.tapToPay, color = TextPrimary, fontSize = d.body, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(d.gap))
        BigButton(s.payButton, KioskAmber, onPay)
        Spacer(Modifier.height(8.dp))
        CancelButton(s.cancel, onCancel)
    }
}

@Composable
private fun DispensingScreen(s: VendStrings, item: String, price: String) {
    val d = LocalDims.current
    Screen {
        CircularProgressIndicator(color = KioskEmerald, strokeWidth = 5.dp, modifier = Modifier.size(d.mark * 0.75f))
        Spacer(Modifier.height(d.gap))
        Text(s.dispensing, color = TextPrimary, fontSize = d.title, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text("${s.item(item)} · $price", color = TextSecondary, fontSize = d.body)
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
        Text(s.takeItem, color = TextPrimary, fontSize = d.title, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(s.charged(price), color = TextSecondary, fontSize = d.body)
        Spacer(Modifier.height(2.dp))
        Text(s.thanks, color = KioskEmerald, fontSize = d.body, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NotChargedScreen(s: VendStrings, reason: Reason, limit: String) {
    val d = LocalDims.current
    val why = s.reason(reason, limit)
    Screen {
        ResultMark(false)
        Spacer(Modifier.height(d.gap))
        Text(why, color = TextPrimary, fontSize = d.headline, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center, lineHeight = d.headline * 1.2f)
        Spacer(Modifier.height(6.dp))
        Text(s.notCharged, color = KioskEmerald, fontSize = d.body, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun UnavailableScreen() {
    val d = LocalDims.current
    // Both languages at once: nobody can act on this screen, so no toggle.
    Screen {
        ResultMark(false)
        Spacer(Modifier.height(d.gap))
        for (l in listOf(VendStrings.EN, VendStrings.FR)) {
            Text(l.unavailableTitle, color = TextPrimary, fontSize = d.body * 1.2f, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text(l.unavailableBody, color = TextSecondary, fontSize = d.small, textAlign = TextAlign.Center)
            Spacer(Modifier.height(d.gap))
        }
    }
}
