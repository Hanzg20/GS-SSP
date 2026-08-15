package com.goldsky.ssp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.goldsky.ssp.vending.VendingViewModel
import com.goldsky.ssp.vending.ui.VendingMainContainer
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.TtsManager

/**
 * Universal Entry Point for Vending Flavor.
 * Automatically adapts to IM25 (Compose UI) or legacy terminals.
 */
class MainActivity : ComponentActivity() {

    private val vendingViewModel: VendingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize core services (shared with wash flavor)
        DeviceRepository.init(this)
        TtsManager.registerLifecycle(this, this)

        // Set Compose Content
        setContent {
            VendingMainContainer(vendingViewModel)
        }

        // TODO: Start MDB listener for Vending Logic
    }
}
