package com.goldsky.ssp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.goldsky.ssp.vending.VendingViewModel
import com.goldsky.ssp.vending.ui.VendingMainContainer
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.payment.SupabaseClientProvider
import com.goldsky.ssp.payment.hardware.HardwareFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.goldsky.ssp.common.TtsManager

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

        // Device identity + heartbeat, like every other shell: persist the SN
        // (HeartbeatWorker reads it), schedule heartbeat / offline replay /
        // batch close (heartbeat also goes out now and on every reconnect),
        // then register and link the device so its heartbeats pass RLS.
        val deviceSn = try {
            HardwareFactory.getHardwareProvider(DeviceRepository.getPersistedHardwareVendor()).getSerialNumber(this)
        } catch (e: Exception) {
            "MOCK_SN_${System.currentTimeMillis()}"
        }
        DeviceRepository.persistDeviceSn(deviceSn)
        AdManager.init(this)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        CoroutineScope(Dispatchers.IO).launch {
            SupabaseClientProvider.ensureAuthenticated()
            DeviceRepository.registerDevice(deviceSn, version)
            DeviceRepository.syncDeviceIdentity(deviceSn)
        }

        // Set Compose Content
        setContent {
            VendingMainContainer(vendingViewModel)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        simulate(intent)
    }

    // Full screen like the other Aegis kiosks: the Q3mini's ~320dp square has no room for system bars.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        simulate(intent)
    }

    /**
     * Simulator, emulator only: no button on screen, only adb, and the ViewModel
     * ignores it unless the build is debuggable and off the Q3mini, e.g.
     * `adb shell am start -n com.goldsky.ssp.aegis.vend/com.goldsky.ssp.MainActivity --es sim select:225:14`
     * (also start / pay / fail / decline).
     */
    private fun simulate(intent: Intent?) {
        intent?.getStringExtra("sim")?.let { vendingViewModel.simulate(it) }
    }
}
