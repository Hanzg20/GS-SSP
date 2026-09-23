package com.goldsky.ssp.feature.timer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.goldsky.ssp.DeviceAdapter
import com.goldsky.ssp.payment.hardware.HardwareFactory

/**
 * Entry point for Aegis Timer (time-based products, first one: the self-service
 * vacuum). For now this only
 * hosts [HoldTestScreen] -- the on-site output test that decides how the
 * customer flow (card-only, $2/4min or $3/5min, terminal-timed hold,
 * countdown, alarm-only on fault) will drive the machine. No payment yet.
 */
class MainActivity : ComponentActivity() {

    private val vm: HoldTestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val vendor = DeviceAdapter.getRecommendedVendor()
        // Resolved with this Activity's Context, same as DispenseEngine does
        // for wash (WizarPOS binds its terminal service through it).
        vm.attach(HardwareFactory.getGpioProvider(this, vendor), vendor)

        setContent { HoldTestScreen(vm) }
    }
}
