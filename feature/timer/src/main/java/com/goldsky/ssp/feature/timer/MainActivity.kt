package com.goldsky.ssp.feature.timer

import android.widget.Toast
import android.content.Intent
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
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DeviceAccessManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.DiagnosticManager
import com.goldsky.ssp.payment.LauncherClaim
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.payment.PendingResolver
import com.goldsky.ssp.payment.RemoteCommandManager
import com.goldsky.ssp.payment.ShadowManager
import com.goldsky.ssp.payment.SupabaseClientProvider
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IHardwareProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Aegis Timer: time-based self-service products (first one: the vacuum beside
 * the wash). Customer flow lives in [TimerViewModel]; the on-site output test
 * ([HoldTestScreen]) is behind the technician PIN, reached by tapping the
 * version label 3 times -- same gesture as wash.
 */
class MainActivity : ComponentActivity() {

    private val timerVm: TimerViewModel by viewModels()
    private val holdVm: HoldTestViewModel by viewModels()
    private val selfTestVm: PaymentSelfTestViewModel by viewModels()
    private val outputSettings by lazy { OutputSettingsStore(this) }
    private var deviceSn = ""
    private var startedDual = false

    // Hoisted so the idle-ad timer can tell when the technician panel or the
    // PIN pad is open (never cover those with ads).
    private val showPinState = mutableStateOf(false)
    private val techState = mutableStateOf(false)
    private val adHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val adRunnable = Runnable { launchAdsIfIdle() }
    private var watchdogJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setupCrashHandler()
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
        val payment = PaymentProviderFactory.getPaymentProvider(this, vendor)
        // Fixed for this process: switching single/dual in the technician
        // panel restarts the app (see onTechExit).
        val dual = outputSettings.dualBay
        startedDual = dual
        val bayOutputs = Bay.entries.associateWith { bay -> ConfigurableOutput(gpio) { outputSettings.load(bay) } }
        timerVm.attach(
            payment = payment,
            output = ConfigurableOutput(gpio) { outputSettings.load() },
            hardwareOk = { hardware.isOperational() },
            dual = dual,
            bayOutput = { bayOutputs.getValue(it) },
        )

        deviceSn = runCatching { hardware.getSerialNumber(this) }
            .onFailure { Log.e(TAG, "Failed to read hardware SN: ${it.message}") }
            .getOrDefault("")
        DeviceRepository.persistDeviceSn(deviceSn)
        timerVm.setDeviceSn(deviceSn)
        syncIdentityAndConfig()

        LauncherClaim.ensureDefault(this, vendor)
        com.goldsky.ssp.payment.AppSwitcher.claimHomeIfSwitched(this)
        RemoteCommandManager.startListening(this, deviceSn, vendor, object : RemoteCommandManager.CommandListener {
            override fun onSyncRequested() = loadConfig(DeviceRepository.getPersistedOrgId())
            // A LOCK mid-session lets the paid session finish; it only blocks new sales.
            override fun onLockRequested(locked: Boolean) = timerVm.setLocked(DeviceAccessManager.isLocked())
            override suspend fun onStartServiceRequested(productId: String?, startHex: String?, commandId: String, bay: String?) =
                timerVm.startRemoteSession(productId, commandId, bay)
        })
        ShadowManager.startSync(this, deviceSn)
        // Heartbeat, offline transaction replay, daily batch close, storage
        // cleaning and ad sync -- same jobs wash schedules.
        AdManager.init(this)
        startWatchdog(hardware)
        // Card sales left PENDING by a crash: reverse the approved ones,
        // decline the rest. Only while idle on Home, never mid-sale.
        lifecycleScope.launch {
            delay(15_000)
            if (timerVm.canEnterTechMode) runCatching { PendingResolver.resolve(this@MainActivity) }
        }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

        setContent {
            val s by timerVm.state.collectAsState()
            val selfTest by selfTestVm.state.collectAsState()
            var showPin by showPinState
            var tech by techState
            var outSettings by remember { mutableStateOf(outputSettings.load()) }
            var dualSetting by remember { mutableStateOf(outputSettings.dualBay) }
            val switchTarget = remember { com.goldsky.ssp.payment.AppSwitcher.other(this@MainActivity) }
            var bayOutSettings by remember { mutableStateOf(Bay.entries.associateWith { outputSettings.load(it) }) }
            BackHandler(enabled = true) { if (tech) tech = false } // kiosk: back never leaves the app

            if (tech) {
                TechScreen(
                    demoMode = s.demoMode,
                    onDemoChange = timerVm::setDemoMode,
                    onExit = { holdVm.forceOff(); tech = false; onTechExit() },
                    selfTest = selfTest,
                    onRunSelfTest = { selfTestVm.run(payment, deviceSn) },
                    onSettle = { selfTestVm.settle() },
                    outputSettings = outSettings,
                    onOutputChange = { outputSettings.save(it); outSettings = it },
                    onTestOutput = { testOutput(gpio) },
                    holdTest = { HoldTestScreen(holdVm) },
                    dualBay = dualSetting,
                    onDualChange = { outputSettings.dualBay = it; dualSetting = it },
                    baySettings = bayOutSettings,
                    onBayChange = { bay, v -> outputSettings.save(bay, v); bayOutSettings = bayOutSettings + (bay to v) },
                    onTestBay = { testBay(gpio, it) },
                    switchTarget = switchTarget?.second,
                    onSwitchApp = {
                        // Never end a running vacuum session (dual mode reaches tech from Home).
                        if (!timerVm.idleForAds) {
                            Toast.makeText(this@MainActivity, "有吸尘器正在计时，结束后再切换", Toast.LENGTH_LONG).show()
                        } else {
                            holdVm.forceOff()
                            switchTarget?.let { com.goldsky.ssp.payment.AppSwitcher.switchTo(this@MainActivity, it.first) }
                        }
                    },
                )
            } else {
                TimerApp(
                    s = s,
                    version = version,
                    onSelect = timerVm::select,
                    onCancelPayment = timerVm::cancelPayment,
                    onTechTrigger = { if (timerVm.canEnterTechMode) showPin = true },
                    onScan = { startScan(vendor) },
                    onClearPending = timerVm::clearPending,
                    onConfirm = timerVm::confirm,
                    onCancelConfirm = timerVm::cancelConfirm,
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

    // ---- coupon / VIP member code scan (same scanner wash uses) -------------

    private var scanner: com.goldsky.ssp.payment.hardware.IScannerProvider? = null
    private val scanHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scanTimeout = Runnable { stopScan() }

    /** Opens the WizarPOS scanner (its own camera page); gives up after SCAN_TIMEOUT_MS. */
    private fun startScan(vendor: String) {
        stopScan()
        val s = runCatching { HardwareFactory.getScannerProvider(this, vendor) }.getOrNull() ?: return
        scanner = s
        scanHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        s.startScan(object : com.goldsky.ssp.payment.hardware.IScannerProvider.ScanCallback {
            override fun onScanSuccess(result: String) {
                runOnUiThread { stopScan(); timerVm.onScanned(result) }
            }
            override fun onScanFailure(errorMsg: String) {
                Log.d(TAG, "Scan: $errorMsg")
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                if (com.goldsky.ssp.payment.DiagnosticManager.reportScanFailure(deviceSn, errorMsg, elapsed)) {
                    runOnUiThread { timerVm.showNotice(getString(com.goldsky.ssp.core.ui.R.string.toast_scanner_unavailable)) }
                }
            }
        })
    }

    private fun stopScan() {
        scanHandler.removeCallbacks(scanTimeout)
        scanner?.let { runCatching { it.stopScan() } }
        scanner = null
    }

    /** Dual-bay technician test: one pulse on that side. */
    private fun testBay(gpio: com.goldsky.ssp.payment.hardware.IGpioProvider, bay: Bay) {
        val s = outputSettings.load(bay)
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = ConfigurableOutput(gpio) { s }.start(5_000, s.centsPerPulse)
            Log.i(TAG, "Bay test ${bay.code} port=${s.port}: ok=$ok")
        }
    }

    /**
     * Single/dual changed in the technician panel: restart so the customer
     * flow is rebuilt for it (same relaunch the crash handler uses). A side
     * still running keeps going -- its timer board times it.
     */
    private fun onTechExit() {
        if (outputSettings.dualBay == startedDual) return
        Log.i(TAG, "Dual-bay setting changed to ${outputSettings.dualBay}, restarting")
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        exitProcess(0)
    }

    /** Technician "试运行": 5 s in the hold modes, one pulse's worth in coin mode. */
    private fun testOutput(gpio: com.goldsky.ssp.payment.hardware.IGpioProvider) {
        val s = outputSettings.load()
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = ConfigurableOutput(gpio) { s }.start(5_000, s.centsPerPulse)
            Log.i(TAG, "Output test ${s.mode} port=${s.port}: ok=$ok")
        }
    }

    // ---- idle ad screen (same rule as wash's BaseAdActivity) ----------------

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        resetAdTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        resetAdTimer()
    }

    override fun onPause() {
        super.onPause()
        adHandler.removeCallbacks(adRunnable)
    }

    private fun resetAdTimer() {
        adHandler.removeCallbacks(adRunnable)
        adHandler.postDelayed(adRunnable, AD_IDLE_MS)
    }

    /** Only from the idle package screen -- never mid-payment, mid-session or in the technician panel. */
    private fun launchAdsIfIdle() {
        if (!timerVm.idleForAds || techState.value || showPinState.value) {
            resetAdTimer()
            return
        }
        startActivity(Intent(this, com.goldsky.ssp.ui.AdActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** Offline-first: cached org config right away, then refresh once the device's identity is synced. */
    private fun syncIdentityAndConfig() {
        lifecycleScope.launch { timerVm.applyConfig(ConfigManager.loadLocalConfig(this@MainActivity)) }
        loadConfig(DeviceRepository.getPersistedOrgId())
        lifecycleScope.launch {
            SupabaseClientProvider.ensureAuthenticated()
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
            // Tells CMP whether remote start needs a unit (1 / 2) for this terminal.
            DeviceRepository.registerDevice(deviceSn, version, serviceUnits = if (outputSettings.dualBay) 2 else 1)
            val identity = DeviceRepository.syncDeviceIdentity(deviceSn)
            DeviceAccessManager.applyActiveState(identity?.is_active)
            timerVm.setLocked(DeviceAccessManager.isLocked())
            identity?.org_id?.let { loadConfig(it) }
        }
    }

    private fun loadConfig(orgId: String?) {
        lifecycleScope.launch {
            val config = ConfigManager.loadConfig(this@MainActivity, orgId)
            // Packages first so the screen never waits on TTS voice setup.
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

    /**
     * Unattended: report the crash, then relaunch. A session that was running
     * survives this via SessionStore -- TimerViewModel forces the output off
     * and resumes the paid time on the next start.
     */
    private fun setupCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e(TAG, "Crash, restarting", throwable)
            runCatching {
                val job = DiagnosticManager.reportError(deviceSn, "APP_CRASH", severity = "CRITICAL", trace = throwable.stackTraceToString())
                // Bounded wait so the report can leave before the process dies.
                runBlocking { withTimeoutOrNull(2000) { job.join() } }
            }
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            exitProcess(1)
        }
    }

    /** Same 15s hardware-watchdog feed as wash. */
    private fun startWatchdog(hardware: IHardwareProvider) {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { hardware.feedWatchdog() }.onFailure { Log.e(TAG, "Watchdog feed failed: ${it.message}") }
                delay(15_000)
            }
        }
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        RemoteCommandManager.stopListening()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AegisTimer"
        /** Same idle time as wash (BaseAdActivity). */
        const val AD_IDLE_MS = 180_000L
        const val SCAN_TIMEOUT_MS = 30_000L
    }
}
