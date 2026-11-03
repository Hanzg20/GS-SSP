package com.goldsky.ssp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.common.TtsManager
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DeviceAccessManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.DiagnosticManager
import com.goldsky.ssp.payment.LauncherClaim
import com.goldsky.ssp.payment.RemoteCommandManager
import com.goldsky.ssp.payment.ShadowManager
import com.goldsky.ssp.payment.SupabaseClientProvider
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IHardwareProvider
import com.goldsky.ssp.vending.VendingViewModel
import com.goldsky.ssp.vending.ui.VendPinDialog
import com.goldsky.ssp.vending.ui.VendTechScreen
import com.goldsky.ssp.vending.ui.VendingMainContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Aegis Vend: the card terminal on a vending machine (Q3mini UPT, MDB). The vend loop is
 * [VendingViewModel]; this Activity is the unattended-kiosk shell around it, the same as
 * Aegis Timer's: HOME + boot recovery, config / technician PIN from Cael, remote commands
 * (lock, sync, bring to front), heartbeat, idle ads, crash restart, and the technician
 * panel behind the four corners + PIN.
 */
class MainActivity : ComponentActivity() {

    private val vendingViewModel: VendingViewModel by viewModels()
    private val showcaseViewModel: com.goldsky.ssp.vending.ShowcaseViewModel by viewModels()
    /** Technician panel: the 6-item showcase instead of the machine's MDB sales. */
    private val showcaseState = mutableStateOf(false)
    private val lockedState = mutableStateOf(false)

    /** No payment or vend under way on whichever screen is up. */
    private val idle: Boolean
        get() = vendingViewModel.idle && (!showcaseState.value || showcaseViewModel.idle)
    private var deviceSn = ""

    private val showPinState = mutableStateOf(false)
    private val techState = mutableStateOf(false)
    private val techEntry = com.goldsky.ssp.ui.CornerSequence()
    private val adHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val adRunnable = Runnable { launchAdsIfIdle() }
    private var watchdogJob: Job? = null
    private lateinit var bigScreen: com.goldsky.ssp.vending.ui.BigScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        setupCrashHandler()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        DeviceRepository.init(this)
        DeviceAccessManager.init(this)
        TtsManager.registerLifecycle(this, this)
        setShowcase(com.goldsky.ssp.vending.SaleMode.get(this) == com.goldsky.ssp.vending.SaleMode.Mode.SHOWCASE)
        applyLock() // a remote lock survives a restart

        val vendor = DeviceAdapter.getRecommendedVendor()
        DeviceRepository.persistHardwareVendor(vendor)
        val hardware = HardwareFactory.getHardwareProvider(vendor)
        deviceSn = runCatching { hardware.getSerialNumber(this) }
            .onFailure { Log.e(TAG, "Failed to read hardware SN: ${it.message}") }
            .getOrDefault("")
        if (deviceSn.isNotBlank()) DeviceRepository.persistDeviceSn(deviceSn)
        syncIdentityAndConfig()

        // Unattended: stay the terminal's HOME / WizarPOS boot launcher (see LauncherClaim).
        LauncherClaim.ensureDefault(this, vendor)
        com.goldsky.ssp.ui.BootFrontGuard.install(this)
        // PAYWizard stopped (after a reboot, or by itself): restart it while idle.
        com.goldsky.ssp.ui.PayWizardGuard.start(this) { idle }
        if (deviceSn.isNotBlank()) {
            RemoteCommandManager.startListening(this, deviceSn, vendor, object : RemoteCommandManager.CommandListener {
                override fun onSyncRequested() { lifecycleScope.launch { loadConfig(DeviceRepository.refreshOrgId(deviceSn)) } }
                // A LOCK mid-vend lets that vend finish; it only blocks new ones.
                override fun onLockRequested(locked: Boolean) = applyLock()
                override fun isIdleForScannerTest() = idle
                override suspend fun onBringToFrontRequested(): Pair<Boolean, String>? {
                    if (!com.goldsky.ssp.ui.KioskRecall.handles(application)) return null
                    if (!idle) return false to "Not run: a payment or vend is in progress"
                    return com.goldsky.ssp.ui.KioskRecall.bringBack(application, this@MainActivity.javaClass)
                }
            })
            ShadowManager.startSync(this, deviceSn)
        }
        // Heartbeat, offline replay, batch close, storage cleaning and ad sync -- same jobs as Wash / Timer.
        AdManager.init(this)
        startWatchdog(hardware)
        bigScreen = com.goldsky.ssp.vending.ui.BigScreen(this, vendingViewModel).also { it.start() }
        // Primary (technician panel): Aegis Vend Screen, an Android display beside the machine
        // paired in the panel, follows this screen over the cloud. Standalone sends nothing.
        bigScreen.onRoleChanged = { applyRole(it) }
        applyRole(bigScreen.role)
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(vendingViewModel.uiState, vendingViewModel.french) { _, _ -> }
                .collect { com.goldsky.ssp.payment.VendScreenLink.publish() }
        }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        setContent {
            var showPin by showPinState
            var tech by techState
            val showcase by showcaseState
            val locked by lockedState
            val french by vendingViewModel.french.collectAsState()
            BackHandler(enabled = true) { if (tech) tech = false } // kiosk: back never leaves the app
            if (tech) {
                VendTechScreen(
                    version = version,
                    deviceSn = deviceSn,
                    mdbStatus = { vendingViewModel.mdbStatus() },
                    onSystemCheck = { startActivity(Intent(this, com.goldsky.ssp.ui.SystemCheckActivity::class.java)) },
                    onPaymentTests = { startActivity(Intent(this, com.goldsky.ssp.ui.PaymentTestActivity::class.java)) },
                    bigScreen = bigScreen,
                    onPairScreen = { code ->
                        if (deviceSn.isBlank()) null
                        else com.goldsky.ssp.payment.VendScreenLink.pair(code, deviceSn)
                    },
                    // The system's Cast page: turn on "Enable wireless display" (menu), pick the screen.
                    onCastSettings = {
                        runCatching { startActivity(Intent(android.provider.Settings.ACTION_CAST_SETTINGS)) }
                            .onFailure { Toast.makeText(this, "This terminal has no Cast settings", Toast.LENGTH_LONG).show() }
                    },
                    saleMode = if (showcase) com.goldsky.ssp.vending.SaleMode.Mode.SHOWCASE else com.goldsky.ssp.vending.SaleMode.Mode.MDB,
                    onSaleMode = { mode ->
                        com.goldsky.ssp.vending.SaleMode.set(this, mode)
                        setShowcase(mode == com.goldsky.ssp.vending.SaleMode.Mode.SHOWCASE)
                    },
                    onExit = { tech = false },
                )
            } else if (showcase && !locked) {
                com.goldsky.ssp.vending.ui.ShowcaseContainer(showcaseViewModel, french, vendingViewModel::toggleLanguage)
            } else {
                VendingMainContainer(vendingViewModel, version)
            }
            if (showPin) {
                VendPinDialog(onDismiss = { showPin = false }) { pin ->
                    // The store's PIN from Cael; one shorter than 6 digits opens nothing.
                    val expected = ConfigManager.getConfig()?.settings?.maintenance_pin
                    if (!com.goldsky.ssp.ui.TechPin.isValid(expected)) Toast.makeText(
                        this, "No 6-8 digit technician PIN set for this store -- set it in Cael", Toast.LENGTH_LONG,
                    ).show()
                    com.goldsky.ssp.ui.TechPin.accepts(pin, expected).also { ok -> if (ok) { showPin = false; tech = true } }
                }
            }
        }
        simulate(intent)
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
        val command = intent?.getStringExtra("sim") ?: return
        // VMC-simulator build: pair a network big screen without the technician panel.
        if (command.startsWith("pair:") && vendingViewModel.vmcSimulator && deviceSn.isNotBlank()) {
            bigScreen.role = com.goldsky.ssp.vending.ui.BigScreen.Role.PRIMARY // a big screen = primary
            lifecycleScope.launch {
                val screen = com.goldsky.ssp.payment.VendScreenLink.pair(command.removePrefix("pair:"), deviceSn)
                Log.i(TAG, "Pair ${command.removePrefix("pair:")}: ${screen ?: "no screen answered"}")
            }
            return
        }
        vendingViewModel.simulate(command)
    }

    private fun applyRole(role: com.goldsky.ssp.vending.ui.BigScreen.Role) {
        if (role == com.goldsky.ssp.vending.ui.BigScreen.Role.PRIMARY && deviceSn.isNotBlank()) {
            com.goldsky.ssp.payment.VendScreenLink.startPublishing(deviceSn) {
                com.goldsky.ssp.vending.logic.VendScreenWire.encode(
                    vendingViewModel.uiState.value, vendingViewModel.french.value, vendingViewModel.vmcSimulator,
                    com.goldsky.ssp.vending.VendingViewModel.SESSION_LIMIT_CENTS,
                )
            }
        } else {
            com.goldsky.ssp.payment.VendScreenLink.stopPublishing()
        }
    }

    /** Offline-first: cached org config right away, then refresh once the device's identity is synced. */
    private fun syncIdentityAndConfig() {
        loadConfig(DeviceRepository.getPersistedOrgId())
        if (deviceSn.isBlank()) return
        lifecycleScope.launch {
            SupabaseClientProvider.ensureAuthenticated()
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
            DeviceRepository.registerDevice(deviceSn, version)
            val identity = DeviceRepository.syncDeviceIdentity(deviceSn)
            DeviceAccessManager.applyActiveState(identity?.is_active)
            applyLock()
            identity?.org_id?.let { loadConfig(it) }
        }
    }

    private fun loadConfig(orgId: String?) {
        lifecycleScope.launch {
            val config = ConfigManager.loadConfig(this@MainActivity, orgId)
            TtsManager.setLocale(config.settings.locale_tag)
            showcaseViewModel.refreshItems()
        }
    }

    private fun applyLock() {
        lockedState.value = DeviceAccessManager.isLocked()
        vendingViewModel.setLocked(lockedState.value)
    }

    private fun setShowcase(on: Boolean) {
        showcaseState.value = on
        vendingViewModel.setShowcase(on)
        if (on) showcaseViewModel.refreshItems()
        Log.i(TAG, "Sale mode: ${if (on) "SHOWCASE (6-item grid, card voided)" else "MDB"}")
    }

    // ---- idle ad screen (same rule as wash's BaseAdActivity) ----------------

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        com.goldsky.ssp.ui.BootFrontGuard.onTouch()
        resetAdTimer()
        // Technician screen: the four corners in order within 5 s, only while idle, then the
        // PIN (com.goldsky.ssp.ui.CornerSequence). Only watches -- the touch still lands.
        if (ev?.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val root = window.decorView
            if (!idle || techState.value || showPinState.value) techEntry.reset()
            else if (techEntry.onDown(ev.rawX, ev.rawY, root.width, root.height, android.os.SystemClock.elapsedRealtime())) {
                showPinState.value = true
            }
        }
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

    /** Only from the idle screen -- never mid-payment, mid-vend or in the technician panel. */
    private fun launchAdsIfIdle() {
        if (!idle || techState.value || showPinState.value) {
            resetAdTimer()
            return
        }
        startActivity(Intent(this, com.goldsky.ssp.ui.AdActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // ---- kiosk ----------------------------------------------------------------

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
     * Unattended: report the crash, then relaunch. A vend that was under way is given
     * back on the next start (VendRecovery).
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

    /** Same 15 s hardware-watchdog feed as Wash / Timer. */
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
        bigScreen.stop()
        RemoteCommandManager.stopListening()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AegisVend"
        /** Same idle time as Wash (BaseAdActivity). */
        const val AD_IDLE_MS = 180_000L
    }
}
