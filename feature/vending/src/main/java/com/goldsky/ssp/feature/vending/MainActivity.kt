package com.goldsky.ssp

import android.os.Bundle
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

        // TODO: Start MDB listener for Vending Logic
    }
}
