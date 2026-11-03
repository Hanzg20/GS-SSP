package com.goldsky.ssp.aegis.vend.screen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.VendScreenLink
import com.goldsky.ssp.vending.logic.VendScreenWire
import com.goldsky.ssp.vending.logic.VendingState
import com.goldsky.ssp.vending.ui.BigScreenContent
import com.goldsky.ssp.vending.ui.VendPinDialog
import com.goldsky.ssp.vending.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The big screen. Unpaired: a 6-digit code, typed into the terminal's technician panel.
 * Paired: what that terminal shows, in large type; Cael ads after [AD_IDLE_MS] idle, taken
 * down the moment the machine is in use. Technician panel: the four corners (touch) or the
 * remote's MENU key held, then the store's PIN.
 */
class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("vend_screen", Context.MODE_PRIVATE) }
    private val terminalState = mutableStateOf<String?>(null)
    private val shownState = mutableStateOf<VendScreenWire.Shown?>(null)
    /** Elapsed time of the last message from the terminal; 0 = none yet. */
    private val lastHeardState = mutableLongStateOf(0L)
    private val pinState = mutableStateOf(false)
    private val techState = mutableStateOf(false)
    private val techEntry = com.goldsky.ssp.ui.CornerSequence()

    private var listenJob: Job? = null
    private var adsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        terminalState.value = prefs.getString(KEY_TERMINAL, null)
        terminalState.value?.let { follow(it) }
        watchForAds()

        setContent {
            BackHandler(enabled = true) { if (techState.value) techState.value = false }
            val terminal by terminalState
            val shown by shownState
            val lastHeard by lastHeardState
            var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(Unit) { while (true) { delay(5_000); now = SystemClock.elapsedRealtime() } }

            VendingTheme {
                Box(Modifier.fillMaxSize().background(KioskBg)) {
                    when {
                        techState.value -> TechPanel(terminal, lastHeard, now)
                        terminal == null -> PairingScreen { paired -> pairedWith(paired) }
                        else -> {
                            val offline = lastHeard == 0L || now - lastHeard > OFFLINE_MS
                            val s = shown
                            BigScreenContent(
                                state = if (offline || s == null) VendingState.Idle else s.state,
                                french = s?.french ?: false,
                                simulator = s?.simulator ?: false,
                                status = if (offline) "· offline" else null,
                                limitCents = s?.limitCents ?: 1000,
                            )
                        }
                    }
                    if (pinState.value) {
                        VendPinDialog(onDismiss = { pinState.value = false }) { pin ->
                            val expected = ConfigManager.getConfig()?.settings?.maintenance_pin
                            if (!com.goldsky.ssp.ui.TechPin.isValid(expected)) Toast.makeText(
                                this@MainActivity, "No 6-8 digit technician PIN for this store -- assign this screen to the store in Cael",
                                Toast.LENGTH_LONG,
                            ).show()
                            com.goldsky.ssp.ui.TechPin.accepts(pin, expected).also { ok ->
                                if (ok) { pinState.value = false; techState.value = true }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pairedWith(terminalSn: String) {
        Log.i(TAG, "Paired with terminal $terminalSn")
        prefs.edit().putString(KEY_TERMINAL, terminalSn).apply()
        terminalState.value = terminalSn
        follow(terminalSn)
    }

    private fun follow(terminalSn: String) {
        listenJob?.cancel()
        listenJob = VendScreenLink.listen(terminalSn, lifecycleScope) { json ->
            val shown = VendScreenWire.decode(json) ?: return@listen
            shownState.value = shown
            lastHeardState.longValue = SystemClock.elapsedRealtime()
            // The machine is in use: the vend, not an ad.
            if (shown.state != VendingState.Idle && shown.state != VendingState.Unavailable) backToFront()
        }
    }

    /** Ads after AD_IDLE_MS with the machine idle (or the terminal unreachable). */
    private fun watchForAds() {
        adsJob?.cancel()
        adsJob = lifecycleScope.launch {
            var idleSince = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(5_000)
                val now = SystemClock.elapsedRealtime()
                val heard = lastHeardState.longValue
                val inUse = heard != 0L && now - heard <= OFFLINE_MS &&
                    shownState.value?.state.let { it != null && it != VendingState.Idle && it != VendingState.Unavailable }
                val busy = inUse || terminalState.value == null || techState.value || pinState.value
                if (busy) idleSince = now
                else if (now - idleSince >= AD_IDLE_MS && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    idleSince = now
                    startActivity(Intent(this@MainActivity, com.goldsky.ssp.ui.AdActivity::class.java).putExtra(com.goldsky.ssp.ui.AdActivity.EXTRA_NO_PROMPT, true))
                }
            }
        }
    }

    /** Over the ad player (singleTask: starting this one closes what's above it). */
    private fun backToFront() {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "Back to front refused: ${it.message}") }
    }

    // ---- technician entry: four corners (touch), or MENU held (remote) -----------

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN && !techState.value && !pinState.value) {
            val root = window.decorView
            if (techEntry.onDown(ev.rawX, ev.rawY, root.width, root.height, SystemClock.elapsedRealtime())) pinState.value = true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && event.repeatCount == 0) event.startTracking()
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && !techState.value) {
            pinState.value = true
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    // ---- screens -------------------------------------------------------------------

    @Composable
    private fun PairingScreen(onPaired: (String) -> Unit) {
        val code = remember { (100000..999999).random().toString() }
        val mySn = remember { DeviceRepository.getPersistedDeviceSn().orEmpty() }
        LaunchedEffect(code) {
            val terminal = VendScreenLink.awaitPairing(code, mySn)
            if (terminal.isNotBlank()) onPaired(terminal)
        }
        val unit = unit()
        Column(
            Modifier.fillMaxSize().padding((unit * 4).dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text("GSPay", color = KioskAmber, fontSize = (unit * 5).sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height((unit * 3).dp))
            Text("配对码 · Pairing code", color = TextSecondary, fontSize = (unit * 4).sp)
            Text(code.chunked(3).joinToString(" "), color = TextPrimary, fontSize = (unit * 16).sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            Spacer(Modifier.height((unit * 2).dp))
            Text(
                "在售货机刷卡终端上：技术员面板 → 配对网络大屏 → 输入此码\nOn the vending terminal: technician panel → pair network screen → enter this code",
                color = TextSecondary, fontSize = (unit * 3).sp, textAlign = TextAlign.Center, lineHeight = (unit * 4.5f).sp,
            )
            Spacer(Modifier.height((unit * 3).dp))
            Text("SN $mySn", color = TextSecondary, fontSize = (unit * 2.2f).sp)
        }
    }

    @Composable
    private fun TechPanel(terminal: String?, lastHeard: Long, now: Long) {
        val unit = unit()
        val mySn = remember { DeviceRepository.getPersistedDeviceSn().orEmpty() }
        val version = remember { runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?" }
        Column(Modifier.fillMaxSize().padding((unit * 4).dp), verticalArrangement = Arrangement.spacedBy((unit * 1.5f).dp)) {
            Text("技术员 · Aegis Vend Screen $version", color = KioskAmber, fontSize = (unit * 4).sp, fontWeight = FontWeight.Black)
            Info("本机 SN", mySn)
            Info("配对的终端", terminal ?: "未配对")
            Info("终端状态", when {
                lastHeard == 0L -> "还没收到终端的消息"
                now - lastHeard > OFFLINE_MS -> "离线（${(now - lastHeard) / 1000} 秒没有消息）"
                else -> "在线（${(now - lastHeard) / 1000} 秒前）"
            })
            Row(horizontalArrangement = Arrangement.spacedBy((unit * 2).dp)) {
                PanelButton("重新配对") {
                    listenJob?.cancel()
                    prefs.edit().remove(KEY_TERMINAL).apply()
                    terminalState.value = null
                    shownState.value = null
                    lastHeardState.longValue = 0L
                    techState.value = false
                }
                PanelButton("同步广告") {
                    AdManager.syncNow(this@MainActivity)
                    Toast.makeText(this@MainActivity, "Ad sync started", Toast.LENGTH_SHORT).show()
                }
                PanelButton("返回", primary = true) { techState.value = false }
            }
        }
    }

    @Composable
    private fun Info(label: String, value: String) {
        val unit = unit()
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding((unit * 1.5f).dp)) {
            Text(label, color = TextSecondary, fontSize = (unit * 2.4f).sp)
            Text(value, color = TextPrimary, fontSize = (unit * 3.2f).sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun PanelButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
        val unit = unit()
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(if (primary) KioskAmber else KioskCard)
                .clickable(onClick = onClick).padding(horizontal = (unit * 4).dp, vertical = (unit * 2).dp),
            contentAlignment = Alignment.Center,
        ) { Text(text, color = if (primary) KioskBg else TextPrimary, fontSize = (unit * 3).sp, fontWeight = FontWeight.Bold) }
    }

    @Composable
    private fun unit(): Float {
        val c = LocalConfiguration.current
        return (minOf(c.screenWidthDp, c.screenHeightDp) / 100f).coerceAtLeast(3f)
    }

    // ---- kiosk -----------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private companion object {
        const val TAG = "VendScreen"
        const val KEY_TERMINAL = "terminal_sn"
        /** No message for this long (the terminal repeats every 15 s): the terminal is gone. */
        const val OFFLINE_MS = 45_000L
        /** The big screen is mostly for ads: they start soon after the machine goes idle. */
        const val AD_IDLE_MS = 20_000L
    }
}
