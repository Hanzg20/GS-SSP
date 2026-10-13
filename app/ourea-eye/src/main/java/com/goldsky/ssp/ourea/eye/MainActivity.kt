package com.goldsky.ssp.ourea.eye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
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

                DisposableEffect(Unit) {
                    SocketServerManager.onStateChanged = { newState ->
                        cfdState = newState
                    }
                    SocketServerManager.onRotationChanged = { angle ->
                        rotationAngle = angle
                        requestedOrientation = when (angle) {
                            90 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            180 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            270 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                    }
                    onDispose {
                        SocketServerManager.onStateChanged = null
                        SocketServerManager.onRotationChanged = null
                    }
                }

                OureaEyeScreen(state = cfdState, rotationDegrees = rotationAngle)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SocketServerManager.stop()
    }
}
