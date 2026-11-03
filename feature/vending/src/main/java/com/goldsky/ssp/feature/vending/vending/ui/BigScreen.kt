package com.goldsky.ssp.vending.ui

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.goldsky.ssp.vending.VendingViewModel
import com.goldsky.ssp.vending.logic.VendingState
import com.goldsky.ssp.vending.ui.theme.*
import kotlinx.coroutines.delay

/**
 * This terminal's role (technician panel, owner 2026-11-02): STANDALONE -- no big screen,
 * nothing sent; PRIMARY -- it has one: its state goes to a paired network screen (Aegis Vend
 * Screen, always the secondary) and to a second display if one is connected.
 *
 * A big screen beside the terminal (some machines have one): cast to it wirelessly
 * (Miracast, the system's Cast page) or plugged in. Two modes, set in the technician panel:
 *  - DUAL: the big screen shows the vend in large type ([BigScreenContent]) while the
 *    customer still pays on the terminal;
 *  - MIRROR: the big screen shows what the terminal shows (Android's own mirroring).
 */
class BigScreen(private val activity: Activity, private val viewModel: VendingViewModel) {

    enum class Mode { DUAL, MIRROR }
    enum class Role { STANDALONE, PRIMARY }

    /** Told when the role changes (MainActivity starts / stops sending to the network screen). */
    var onRoleChanged: ((Role) -> Unit)? = null

    var role: Role
        get() = runCatching { Role.valueOf(prefs.getString("role", null) ?: "") }.getOrDefault(Role.STANDALONE)
        set(value) {
            prefs.edit().putString("role", value.name).apply()
            update()
            onRoleChanged?.invoke(value)
        }

    private val displays = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val prefs = activity.getSharedPreferences("vend_big_screen", Context.MODE_PRIVATE)
    private var presentation: Presentation? = null
    private var owner: ScreenOwner? = null

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(Mode.DUAL)
        set(value) {
            prefs.edit().putString("mode", value.name).apply()
            update()
        }

    /** The network big screen last paired in the technician panel (for display only). */
    var pairedScreen: String?
        get() = prefs.getString("net_screen_sn", null)
        set(value) { prefs.edit().putString("net_screen_sn", value).apply() }

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = update()
        override fun onDisplayRemoved(displayId: Int) = update()
        override fun onDisplayChanged(displayId: Int) {}
    }

    fun start() {
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        update()
    }

    fun stop() {
        displays.unregisterDisplayListener(listener)
        hide()
    }

    /** The connected big screen, if any. */
    fun display(): Display? = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()

    /** One line for the technician panel. */
    fun describe(): String {
        val d = display() ?: return "未连接大屏"
        val m = d.mode
        return "已连接：${d.name}（${m.physicalWidth}×${m.physicalHeight}）· " + if (mode == Mode.DUAL) "双屏" else "镜像"
    }

    private fun update() {
        val d = display()
        if (d == null || role == Role.STANDALONE || mode == Mode.MIRROR || activity.isFinishing) {
            hide()
            return
        }
        if (presentation?.display?.displayId == d.displayId) return
        hide()
        Log.i(TAG, "Big screen: ${d.name} (${d.displayId}), dual")
        val o = ScreenOwner().also { it.start() }
        val p = Presentation(activity, d)
        val view = ComposeView(p.context).apply {
            setContent {
                val state by viewModel.uiState.collectAsState()
                val french by viewModel.french.collectAsState()
                val flow by viewModel.purchaseFlow.collectAsState()
                BigScreenContent(state, french, viewModel.vmcSimulator, flow = flow)
            }
        }
        p.setContentView(view)
        // A Presentation is a dialog, not a ComponentActivity: Compose needs a lifecycle of its
        // own -- always resumed, so it keeps updating while the idle ads cover the terminal.
        p.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(o)
            it.setViewTreeSavedStateRegistryOwner(o)
        }
        p.setOnDismissListener { if (presentation === p) { presentation = null; owner?.stop(); owner = null } }
        runCatching { p.show() }.onFailure {
            Log.w(TAG, "Big screen show failed: ${it.message}")
            o.stop()
            return
        }
        presentation = p
        owner = o
    }

    private fun hide() {
        val p = presentation ?: return
        presentation = null
        runCatching { p.dismiss() }
        owner?.stop()
        owner = null
    }

    private class ScreenOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun start() {
            saved.performRestore(null as Bundle?)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TAG = "VendBigScreen"
    }
}

/**
 * The vend on the big screen: the same states as the terminal, in large type, and no
 * buttons -- the customer pays and touches only the terminal, so every screen here
 * points to it. Used by the terminal's own second display and by Aegis Vend Screen
 * (a separate Android display, over the network). [status]: a line for the corner,
 * e.g. that the terminal can't be reached.
 */
@Composable
fun BigScreenContent(
    state: VendingState, french: Boolean, simulator: Boolean, status: String? = null,
    limitCents: Int = VendingViewModel.SESSION_LIMIT_CENTS,
    flow: com.goldsky.ssp.vending.logic.PurchaseFlow = com.goldsky.ssp.vending.logic.PurchaseFlow.BOTH,
) {
    val s = if (french) VendStrings.FR else VendStrings.EN
    val money = { cents: Int -> VendStrings.money(cents, french) }
    // Sized from the shorter side: landscape TVs and portrait signage both fill.
    val conf = LocalConfiguration.current
    val unit = (minOf(conf.screenWidthDp, conf.screenHeightDp) / 100f).coerceAtLeast(3f)
    val big = (unit * 10).sp
    val mid = (unit * 6).sp

    // The terminal's look (VIP page's black and gold): one big line, at most one more.
    Box(Modifier.fillMaxSize().background(KioskPage)) {
        Column(Modifier.fillMaxSize().padding((unit * 4).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GSPay", color = KioskAmber, fontSize = mid, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em)
                Spacer(Modifier.weight(1f))
                status?.let { Text(it, color = TextSecondary, fontSize = (unit * 4).sp, fontWeight = FontWeight.Bold) }
                if (simulator) {
                    Text(
                        "VMC SIMULATOR · TEST", color = TextPrimary, fontSize = (unit * 4).sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(KioskRed).padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Crossfade(targetState = state, label = "big") { st ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((unit * 3).dp)) {
                        when (st) {
                            VendingState.Idle -> if (flow == com.goldsky.ssp.vending.logic.PurchaseFlow.ITEM_FIRST) {
                                Line(s.itemFirstTitle, big, KioskAmber)
                                Line(s.itemStep2, mid, TextPrimary)
                            } else {
                                Line(s.startTitle, big, KioskAmber)
                                Line(s.step1, mid, TextPrimary)
                                if (flow == com.goldsky.ssp.vending.logic.PurchaseFlow.BOTH) Line(s.orChooseFirst, mid, TextSecondary)
                            }
                            is VendingState.Authorizing -> {
                                st.amountCents?.let { Line(money(it), big, KioskAmber) }
                                Line(s.authorizing, big, TextPrimary)
                            }
                            is VendingState.SessionOpen -> {
                                Countdown(st.deadline) { Line(s.secondsLeft(it), big, KioskEmerald) }
                                Line(s.chooseOnMachine, big, KioskAmber)
                                Line(s.holdNote(money(st.limitCents)), mid, TextSecondary)
                            }
                            is VendingState.VendRequestReceived -> {
                                Line(s.item(st.itemSlot), mid, TextPrimary)
                                Line(money(st.amountCents), (unit * 16).sp, KioskAmber)
                                Line(s.payAtTerminal, mid, TextPrimary)
                            }
                            is VendingState.Dispensing -> Line(s.dispensing, big, TextPrimary)
                            is VendingState.Success -> {
                                Line(s.takeItem, big, KioskEmerald)
                                Line(s.charged(money(st.amountCents)), mid, TextPrimary)
                            }
                            is VendingState.NotCharged -> {
                                Line(s.reason(st.reason, money(limitCents)), big, TextPrimary)
                                Line(s.notCharged, mid, KioskEmerald)
                            }
                            VendingState.Unavailable -> Line(s.unavailableTitle, big, TextPrimary)
                            VendingState.Booting -> Line("…", big, TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(text: String, size: androidx.compose.ui.unit.TextUnit, color: Color) {
    Text(
        text, color = color, fontSize = size, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em, textAlign = TextAlign.Center,
        lineHeight = size * 1.2f, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Countdown(deadline: Long, content: @Composable (Int) -> Unit) {
    var left by remember(deadline) { mutableIntStateOf(((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)) }
    LaunchedEffect(deadline) {
        while (left > 0) {
            delay(1000)
            left = ((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
        }
    }
    content(left)
}
