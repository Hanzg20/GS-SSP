package com.goldsky.ssp.flex

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.DeviceAdapter
import com.goldsky.ssp.common.TtsManager
import com.goldsky.ssp.flex.ui.FlexContainer
import com.goldsky.ssp.flex.ui.FlexPinDialog
import com.goldsky.ssp.flex.ui.FlexStrings
import com.goldsky.ssp.flex.ui.FlexTechScreen
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Aegis Flex: the card terminal in front of a partner's own machine -- the customer picks and
 * pays here, the partner's controller hands the product over ([FlexViewModel]). This Activity is
 * the unattended-kiosk shell, Aegis Vend's without MDB and the big screen: HOME + boot recovery,
 * config / technician PIN from Cael, remote commands (lock, sync, bring to front), heartbeat,
 * idle ads, crash restart, and the technician panel behind the four corners + PIN.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: FlexViewModel by viewModels()
    private val lockedState = mutableStateOf(false)

    private val idle: Boolean get() = viewModel.idle
    private var deviceSn = ""

    private val showPinState = mutableStateOf(false)
    private val techState = mutableStateOf(false)
    private val techEntry = com.goldsky.ssp.ui.CornerSequence()
    private val adHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val adRunnable = Runnable { launchAdsIfIdle() }
    private var watchdogJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setupCrashHandler()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        DeviceRepository.init(this)
        DeviceAccessManager.init(this)
        TtsManager.registerLifecycle(this, this)
        // VIP member code payment scans with the camera: asked once, at start, not mid-sale.
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1)
        }
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
                // A LOCK mid-sale lets that sale finish; it only blocks new ones.
                override fun onLockRequested(locked: Boolean) = applyLock()
                override fun isIdleForScannerTest() = idle
                override suspend fun onBringToFrontRequested(): Pair<Boolean, String>? {
                    if (!com.goldsky.ssp.ui.KioskRecall.handles(application)) return null
                    if (!idle) return false to "Not run: a payment is in progress"
                    return com.goldsky.ssp.ui.KioskRecall.bringBack(application, this@MainActivity.javaClass)
                }
            })
            ShadowManager.startSync(this, deviceSn)
        }
        // Heartbeat, offline replay, batch close, storage cleaning and ad sync -- same jobs as Wash / Vend.
        AdManager.init(this)
        startWatchdog(hardware)
        speakScreens()

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        setContent {
            var showPin by showPinState
            var tech by techState
            val french by viewModel.french.collectAsState()
            val payMode by viewModel.payMode.collectAsState()
            val items by viewModel.items.collectAsState()
            val controllerReady by viewModel.controllerReady.collectAsState()
            val controllerDetail by viewModel.controllerDetail.collectAsState()
            BackHandler(enabled = true) { if (tech) tech = false } // kiosk: back never leaves the app
            if (tech) {
                FlexTechScreen(
                    version = version,
                    deviceSn = deviceSn,
                    controller = viewModel.controller.description,
                    controllerReady = controllerReady,
                    controllerDetail = controllerDetail,
                    controllerUrl = com.goldsky.ssp.flex.logic.FlexController.url(this),
                    controllerToken = com.goldsky.ssp.flex.logic.FlexController.token(this),
                    onSetController = viewModel::setController,
                    onTestController = { lifecycleScope.launch { viewModel.checkController() } },
                    products = items.size,
                    onSystemCheck = { openAway(com.goldsky.ssp.ui.SystemCheckActivity::class.java) },
                    onPaymentTests = { openAway(com.goldsky.ssp.ui.PaymentTestActivity::class.java) },
                    onExit = { tech = false },
                )
            } else {
                FlexContainer(
                    viewModel, french, viewModel::toggleLanguage,
                    card = payMode != com.goldsky.ssp.model.PaymentMethodMode.SCAN_ONLY,
                    vip = payMode != com.goldsky.ssp.model.PaymentMethodMode.CARD_ONLY,
                    controller = viewModel.controller.connected,
                )
            }
            if (showPin) {
                FlexPinDialog(onDismiss = { showPin = false }) { pin ->
                    // The store's PIN from Cael; one shorter than 6 digits opens nothing.
                    val expected = ConfigManager.getConfig()?.settings?.maintenance_pin
                    if (!com.goldsky.ssp.ui.TechPin.isValid(expected)) Toast.makeText(
                        this, "No 6-8 digit technician PIN set for this store -- set it in Cael", Toast.LENGTH_LONG,
                    ).show()
                    com.goldsky.ssp.ui.TechPin.accepts(pin, expected).also { ok -> if (ok) { showPin = false; tech = true } }
                }
            }
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
            // No devices.vertical_type for Flex yet (its CHECK constraint): left as it is.
            DeviceAccessManager.applyActiveState(identity?.is_active)
            applyLock()
            identity?.org_id?.let { loadConfig(it) }
        }
    }

    private fun loadConfig(orgId: String?) {
        lifecycleScope.launch {
            val config = ConfigManager.loadConfig(this@MainActivity, orgId)
            TtsManager.setLocale(config.settings.locale_tag)
            viewModel.refreshItems()
            viewModel.refreshPayMode()
        }
    }

    /**
     * Another of our screens over this one (technician checks): sales pause until we're back in
     * front (onResume), so the card reader isn't wanted twice.
     */
    private fun openAway(screen: Class<*>) {
        viewModel.setAway(true)
        runCatching { startActivity(Intent(this, screen)) }.onFailure {
            Log.e(TAG, "Can't open ${screen.simpleName}: ${it.message}")
            viewModel.setAway(false)
        }
    }

    /**
     * Voice (as Vend): each main screen's words, said three times (TtsManager.speakRepeated),
     * in the screen's language. Back to the grid after a result says nothing, so the result's
     * own three times aren't cut short; Unavailable and the technician panel are silent.
     */
    private fun speakScreens() {
        val tech = androidx.compose.runtime.snapshotFlow { techState.value }
        lifecycleScope.launch {
            var spoken: Pair<String?, Boolean>? = null
            var afterResult = false
            var french: Boolean? = null
            kotlinx.coroutines.flow.combine(viewModel.state, viewModel.french, tech) { st, fr, t -> Triple(st, fr, t) }
                .collect { (st, fr, t) ->
                    val s = if (fr) FlexStrings.FR else FlexStrings.EN
                    val wasResult = afterResult
                    val line: String? = if (t) null else when (st) {
                        FlexViewModel.State.Unavailable -> null
                        FlexViewModel.State.Choosing -> if (wasResult) KEEP else s.sayChoose
                        is FlexViewModel.State.Authorizing -> s.sayTapCard
                        is FlexViewModel.State.Scanning -> s.sayScan
                        is FlexViewModel.State.Dispensing -> s.sayDispensing
                        is FlexViewModel.State.Done -> s.sayTakeItem
                        is FlexViewModel.State.NotDispensed ->
                            s.sayNotDispensed + if (st.givenBack && !st.vip) " " + s.sayNotCharged else ""
                        is FlexViewModel.State.Declined ->
                            st.reason?.let { sentence(s.reason(it)) + " " + s.sayNotCharged } ?: s.sayNotPaid
                    }
                    afterResult = !t && (st is FlexViewModel.State.Done || st is FlexViewModel.State.NotDispensed ||
                        st is FlexViewModel.State.Declined)
                    if (line === KEEP) return@collect
                    val key = line to fr
                    if (key == spoken) return@collect
                    spoken = key
                    if (line == null) { TtsManager.cancelRepeat(); return@collect }
                    if (french != fr) {
                        french = fr
                        TtsManager.setLocale(if (fr) "fr-CA" else ConfigManager.getConfig()?.settings?.locale_tag ?: "en-US")
                        // The voice is switched on the engine's own thread; give it a moment.
                        delay(VOICE_SWITCH_MS)
                    }
                    // Just started: the engine takes a few seconds; the first line waits for it.
                    withTimeoutOrNull(TTS_READY_MS) { while (!TtsManager.isReady()) delay(200) }
                    TtsManager.speakRepeated(line)
                }
        }
    }

    /** "CARD DECLINED" -> "Card declined.", so the engine reads words, not letters. */
    private fun sentence(text: String) =
        text.lowercase().replaceFirstChar { it.uppercase() }.trimEnd('.') + "."

    private fun applyLock() {
        lockedState.value = DeviceAccessManager.isLocked()
        viewModel.setLocked(lockedState.value)
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
        viewModel.setAway(false)
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

    /** Only from the idle screen -- never mid-payment or in the technician panel. */
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
     * Unattended: report the crash, then relaunch. A sale that was under way is given
     * back on the next start (FlexRecovery).
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

    /** Same 15 s hardware-watchdog feed as Wash / Vend. */
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
        const val TAG = "AegisFlex"
        /** "Say nothing new, let what's being said finish": compared by identity. */
        val KEEP = String(charArrayOf('-'))
        const val VOICE_SWITCH_MS = 500L
        const val TTS_READY_MS = 10_000L
        /** Same idle time as Wash (BaseAdActivity). */
        const val AD_IDLE_MS = 180_000L
    }
}
