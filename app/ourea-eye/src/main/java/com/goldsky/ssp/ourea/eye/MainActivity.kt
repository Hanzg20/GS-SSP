package com.goldsky.ssp.ourea.eye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import com.goldsky.ssp.ourea.eye.ui.OureaEyeScreen
import com.goldsky.ssp.ourea.eye.ui.TechnicianHotspot
import com.goldsky.ssp.ourea.eye.ui.TechnicianPanel
import com.goldsky.ssp.ourea.eye.ui.TechnicianPinPad
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.ui.theme.GoldSkyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SocketServerManager.start()
        // The customer display is this terminal's HOME, so it comes back by itself after a reboot.
        com.goldsky.ssp.payment.LauncherClaim.ensureDefault(this, "WIZARPOS")
        // WizarPOS OPC opens on top of HOME ~9 s after boot; this brings the Eye back.
        com.goldsky.ssp.ui.BootFrontGuard.install(this)

        setContent {
            GoldSkyTheme(dark = true) {
                var cfdState by remember { mutableStateOf<CfdDisplayState>(CfdDisplayState.Idle) }
                // Remembered here, so the display comes back the right way up after a restart
                // without waiting for Ourea. 0 or 180 only.
                val prefs = remember { getSharedPreferences("ourea_eye", MODE_PRIVATE) }
                var rotationAngle by remember { mutableStateOf(if (prefs.getInt("rotation", 0) == 180) 180 else 0) }
                // The total of the sale just paid, for the thank-you.
                var lastTotal by remember { mutableStateOf<Int?>(null) }
                val scope = rememberCoroutineScope()
                var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                // Technician mode: PIN once, then the hotspot reopens the panel (after a
                // preview) until Exit or a real sale arrives.
                var techScreen by remember { mutableStateOf<String?>(null) } // "pin" | "panel"
                var techUnlocked by remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    SocketServerManager.onStateChanged = { newState ->
                        // A sale has started: never pull the Eye over PAYWizard's card screen.
                        if (newState !is CfdDisplayState.Idle) com.goldsky.ssp.ui.BootFrontGuard.onTouch()
                        when (newState) {
                            // The thank-you stays THANK_YOU_MS: Ourea sends "idle" the moment
                            // its cart empties, which used to cut it off at once. "Idle" during
                            // the hold just waits; a new sale (cart/payment) ends it at once.
                            is CfdDisplayState.ThankYou -> {
                                cfdState = newState
                                // The sale is done: PAYWizard's result page (or OPC's own
                                // screen) may still cover us -- the thank-you is ours to show.
                                backToFront()
                                holdJob?.cancel()
                                holdJob = scope.launch {
                                    kotlinx.coroutines.delay(com.goldsky.ssp.ourea.eye.ui.THANK_YOU_MS)
                                    if (cfdState is CfdDisplayState.ThankYou) cfdState = CfdDisplayState.Idle
                                }
                            }
                            is CfdDisplayState.Idle -> if (holdJob?.isActive != true) cfdState = newState
                            else -> {
                                holdJob?.cancel()
                                techScreen = null; techUnlocked = false // a sale always wins
                                when (newState) {
                                    is CfdDisplayState.Cart -> lastTotal = newState.totalCents
                                    is CfdDisplayState.Payment -> lastTotal = newState.amountCents
                                    is CfdDisplayState.Tip -> lastTotal = newState.totalCents
                                    else -> {}
                                }
                                cfdState = newState
                            }
                        }
                    }
                    // Rotation turns the content only. Also switching the window's
                    // orientation, with the status bar still there, left a non-square
                    // window the rotated content couldn't cover: black strips left/right.
                    SocketServerManager.onRotationChanged = { angle ->
                        rotationAngle = if (angle == 180) 180 else 0
                        prefs.edit().putInt("rotation", rotationAngle).apply()
                    }
                    onDispose {
                        SocketServerManager.onStateChanged = null
                        SocketServerManager.onRotationChanged = null
                    }
                }

                OureaEyeScreen(
                    state = cfdState, rotationDegrees = rotationAngle, paidCents = lastTotal,
                    onTipChosen = { tip, cents ->
                        SocketServerManager.onTipChosen(tip.tipId, cents)
                        lastTotal = tip.totalCents + cents
                        // Card next: straight to "tap your card"; cash / QR / member: the amount.
                        cfdState = CfdDisplayState.Payment(tip.totalCents + cents, cardPrompt = tip.cardNext)
                    }
                )

                Box(Modifier.fillMaxSize().graphicsLayer(rotationZ = rotationAngle.toFloat())) {
                    when (techScreen) {
                        "pin" -> TechnicianPinPad(
                            onSubmit = { pin ->
                                val expected = ConfigManager.getConfig()?.settings?.maintenance_pin?.takeIf { it.isNotBlank() } ?: "1234"
                                (pin == expected).also { if (it) { techUnlocked = true; techScreen = "panel" } }
                            },
                            onDismiss = { techScreen = null }
                        )
                        "panel" -> TechnicianPanel(
                            rotation = rotationAngle,
                            onRotate = { angle ->
                                rotationAngle = angle
                                prefs.edit().putInt("rotation", angle).apply()
                            },
                            onPreview = { preview ->
                                holdJob?.cancel()
                                if (preview is CfdDisplayState.ThankYou) lastTotal = 1862
                                cfdState = preview
                                techScreen = null
                            },
                            onExit = { techScreen = null; techUnlocked = false }
                        )
                        else -> TechnicianHotspot { techScreen = if (techUnlocked) "panel" else "pin" }
                    }
                }
            }
        }
    }

    // Full screen like the Aegis kiosks (BaseAdActivity.applyKioskWindowFlags): no
    // status or navigation bar, so the whole 480x480 panel is the customer display.
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        SocketServerManager.stop()
    }

    /**
     * Back over PAYWizard once a payment is over (094, 2026-10-14: its result page
     * stayed on screen and the customer never saw our thank-you). Allowed from the
     * background while this app is HOME or may draw over other apps.
     */
    private fun backToFront() {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        runCatching {
            startActivity(android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { android.util.Log.w("OureaEye", "Back to front refused: ${it.message}") }
    }
}
