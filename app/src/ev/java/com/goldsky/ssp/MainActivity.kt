package com.goldsky.ssp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.goldsky.ssp.ev.EvViewModel
import com.goldsky.ssp.ev.ui.EvMainContainer
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.TtsManager

/**
 * Entry point for EV Charging product flavor.
 * Adapts GS-SSP core logic to Electric Vehicle charging workflows.
 */
class MainActivity : ComponentActivity() {

    private val evViewModel: EvViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init Core SSP Services
        DeviceRepository.init(this)
        TtsManager.registerLifecycle(this, this)

        setContent {
            EvMainContainer(evViewModel)
        }
    }
}
