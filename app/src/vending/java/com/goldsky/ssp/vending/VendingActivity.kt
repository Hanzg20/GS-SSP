package com.goldsky.ssp.vending

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.goldsky.ssp.vending.ui.VendingMainContainer

/**
 * Entry point for IM25 Vending product flavor.
 * Driven by Compose and MVI state.
 */
class VendingActivity : ComponentActivity() {

    private val viewModel: VendingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            VendingMainContainer(viewModel)
        }

        // TODO: Initialize MDB Listener here once SDK arrives
        // For testing, simulate a request after 3 seconds
        /*
        lifecycleScope.launch {
            delay(3000)
            viewModel.onMdbVendRequest(200, "A1")
        }
        */
    }
}
