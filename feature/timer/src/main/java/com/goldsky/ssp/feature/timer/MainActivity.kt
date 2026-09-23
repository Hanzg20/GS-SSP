package com.goldsky.ssp.feature.timer

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.DeviceAdapter
import com.goldsky.ssp.common.TtsManager
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DeviceAccessManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.payment.RemoteCommandManager
import com.goldsky.ssp.payment.ShadowManager
import com.goldsky.ssp.payment.SupabaseClientProvider
import com.goldsky.ssp.payment.hardware.HardwareFactory
import kotlinx.coroutines.launch

/**
 * Aegis Timer: time-based self-service products (first one: the vacuum beside
 * the wash). Customer flow lives in [TimerViewModel]; the on-site output test
 * ([HoldTestScreen]) is behind the technician PIN, reached by tapping the
 * version label 3 times -- same gesture as wash.
 */
class MainActivity : ComponentActivity() {

    private val timerVm: TimerViewModel by viewModels()
    private val holdVm: HoldTestViewModel by viewModels()
    private var deviceSn = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        TtsManager.registerLifecycle(this, this)
        DeviceRepository.init(this)
        DeviceAccessManager.init(this)

        val vendor = DeviceAdapter.getRecommendedVendor()
        DeviceRepository.persistHardwareVendor(vendor)
        val hardware = HardwareFactory.getHardwareProvider(vendor)
        hardware.init(this)
        hardware.registerLifecycle(this, this)

        // Resolved with this Activity's Context, same as wash does.
        val gpio = HardwareFactory.getGpioProvider(this, vendor)
        holdVm.attach(gpio, vendor)
        timerVm.attach(
            payment = PaymentProviderFactory.getPaymentProvider(this, vendor),
            output = RelayHoldOutput(gpio),
            hardwareOk = { hardware.isOperational() },
        )

        deviceSn = runCatching { hardware.getSerialNumber(this) }
            .onFailure { Log.e(TAG, "Failed to read hardware SN: ${it.message}") }
            .getOrDefault("")
        DeviceRepository.persistDeviceSn(deviceSn)
        timerVm.setDeviceSn(deviceSn)
        syncIdentityAndConfig()

        RemoteCommandManager.startListening(this, deviceSn, vendor, object : RemoteCommandManager.CommandListener {
            override fun onSyncRequested() = loadConfig(DeviceRepository.getPersistedOrgId())
            // A LOCK mid-session lets the paid session finish; it only blocks new sales.
            override fun onLockRequested(locked: Boolean) = timerVm.setLocked(DeviceAccessManager.isLocked())
        })
        ShadowManager.startSync(this, deviceSn)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

        setContent {
            val s by timerVm.state.collectAsState()
            var showPin by remember { mutableStateOf(false) }
            var tech by remember { mutableStateOf(false) }
            BackHandler(enabled = true) { if (tech) tech = false } // kiosk: back never leaves the app

            if (tech) {
                TechScreen(
                    demoMode = s.demoMode,
                    onDemoChange = timerVm::setDemoMode,
                    onExit = { holdVm.forceOff(); tech = false },
                    holdTest = { HoldTestScreen(holdVm) },
                )
            } else {
                TimerApp(
                    s = s,
                    version = version,
                    onSelect = timerVm::select,
                    onCancelPayment = timerVm::cancelPayment,
                    onTechTrigger = { if (timerVm.canEnterTechMode) showPin = true },
                )
            }
            if (showPin) {
                PinDialog(onDismiss = { showPin = false }) { pin ->
                    val expected = ConfigManager.getConfig()?.settings?.maintenance_pin ?: "1234"
                    (pin == expected).also { ok -> if (ok) { showPin = false; tech = true } }
                }
            }
        }
    }

    /** Offline-first: cached org config right away, then refresh once the device's identity is synced. */
    private fun syncIdentityAndConfig() {
        loadConfig(DeviceRepository.getPersistedOrgId())
        lifecycleScope.launch {
            SupabaseClientProvider.ensureAuthenticated()
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
            DeviceRepository.registerDevice(deviceSn, version)
            val identity = DeviceRepository.syncDeviceIdentity(deviceSn)
            DeviceAccessManager.applyActiveState(identity?.is_active)
            timerVm.setLocked(DeviceAccessManager.isLocked())
            identity?.org_id?.let { loadConfig(it) }
        }
    }

    private fun loadConfig(orgId: String?) {
        lifecycleScope.launch {
            val config = ConfigManager.loadConfig(this@MainActivity, orgId)
            // Packages first: TtsManager.setLocale can stall the main thread
            // for seconds on first TTS-engine init (~10s measured on the Q3mini).
            timerVm.applyConfig(config)
            TtsManager.setLocale(config.settings.locale_tag)
        }
    }

    /** Same legacy immersive flags as BaseAdActivity.applyKioskWindowFlags (verified on the Q3mini). */
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

    override fun onDestroy() {
        RemoteCommandManager.stopListening()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AegisTimer"
    }
}
