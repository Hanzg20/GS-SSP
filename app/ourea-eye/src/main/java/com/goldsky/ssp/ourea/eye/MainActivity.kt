package com.goldsky.ssp.ourea.eye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import com.goldsky.ssp.ourea.eye.ui.OureaEyeScreen
import com.goldsky.ssp.ui.theme.GoldSkyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SocketServerManager.start()

        setContent {
            GoldSkyTheme(dark = true) {
                var cfdState by remember { mutableStateOf<CfdDisplayState>(CfdDisplayState.Idle) }
                var rotationAngle by remember { mutableStateOf(0) }
                // The total of the sale just paid, for the thank-you.
                var lastTotal by remember { mutableStateOf<Int?>(null) }
                val scope = rememberCoroutineScope()
                var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

                DisposableEffect(Unit) {
                    SocketServerManager.onStateChanged = { newState ->
                        when (newState) {
                            // The thank-you stays THANK_YOU_MS: Ourea sends "idle" the moment
                            // its cart empties, which used to cut it off at once. "Idle" during
                            // the hold just waits; a new sale (cart/payment) ends it at once.
                            is CfdDisplayState.ThankYou -> {
                                cfdState = newState
                                holdJob?.cancel()
                                holdJob = scope.launch {
                                    kotlinx.coroutines.delay(com.goldsky.ssp.ourea.eye.ui.THANK_YOU_MS)
                                    if (cfdState is CfdDisplayState.ThankYou) cfdState = CfdDisplayState.Idle
                                }
                            }
                            is CfdDisplayState.Idle -> if (holdJob?.isActive != true) cfdState = newState
                            else -> {
                                holdJob?.cancel()
                                when (newState) {
                                    is CfdDisplayState.Cart -> lastTotal = newState.totalCents
                                    is CfdDisplayState.Payment -> lastTotal = newState.amountCents
                                    else -> {}
                                }
                                cfdState = newState
                            }
                        }
                    }
                    // Rotation turns the content only. Also switching the window's
                    // orientation, with the status bar still there, left a non-square
                    // window the rotated content couldn't cover: black strips left/right.
                    SocketServerManager.onRotationChanged = { angle -> rotationAngle = angle }
                    onDispose {
                        SocketServerManager.onStateChanged = null
                        SocketServerManager.onRotationChanged = null
                    }
                }

                OureaEyeScreen(state = cfdState, rotationDegrees = rotationAngle, paidCents = lastTotal)
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
}
