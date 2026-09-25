package com.goldsky.ssp.feature.timer

import com.goldsky.ssp.payment.hardware.DeclineReason
import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.common.TtsManager
import com.goldsky.ssp.model.AppConfig
import com.goldsky.ssp.payment.CardTypeClassifier
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DeviceAccessManager
import com.goldsky.ssp.payment.DiagnosticManager
import com.goldsky.ssp.payment.TransactionRecord
import com.goldsky.ssp.payment.TransactionRepository
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Aegis Timer customer flow: pick a package -> card -> output held ON for
 * the paid time with an on-screen countdown -> thank-you -> home.
 *
 * Fault policy (agreed 2026-09-23): if the output can't even be switched ON
 * after payment, the customer got nothing, so VOID (REFUND fallback) exactly
 * like wash; once a session is running, faults are alarm-only -- no partial
 * refunds.
 */
class TimerViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Screen {
        object Home : Screen
        data class Paying(val pkg: TimerPackage, val message: String) : Screen
        data class Running(val pkg: TimerPackage, val startedAt: Long, val totalMs: Long) : Screen
        data class Finished(val pkg: TimerPackage) : Screen
        /** Customer-facing outcome only; the raw provider message goes to the log and the transaction row. */
        data class Declined(val reason: DeclineReason) : Screen
        /** [refunded] null while the reversal is still in flight. */
        data class StartFailed(val refunded: Boolean?) : Screen
    }

    data class UiState(
        val screen: Screen = Screen.Home,
        val packages: List<TimerPackage> = emptyList(),
        val title: String = "Self-Service",
        val subtitle: String? = null,
        val locked: Boolean = false,
        val healthy: Boolean = true,
        val demoMode: Boolean = false,
        val now: Long = SystemClock.elapsedRealtime(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val store = SessionStore(app)
    private lateinit var payment: IPaymentProvider
    private lateinit var realOutput: TimerOutput
    private var deviceSn = ""
    private var hardwareOk: () -> Boolean = { true }
    private var attached = false

    private var pendingCardInfo: IPaymentProvider.CardInfo? = null
    private var sessionJob: Job? = null
    private var returnHomeJob: Job? = null

    private val output: TimerOutput get() = if (_state.value.demoMode) DemoOutput else realOutput

    fun attach(payment: IPaymentProvider, output: TimerOutput, hardwareOk: () -> Boolean) {
        this.payment = payment
        this.realOutput = output
        this.hardwareOk = hardwareOk
        if (attached) return
        attached = true
        // UI clock + periodic health refresh.
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                _state.update { it.copy(now = SystemClock.elapsedRealtime()) }
                if (tick++ % 25 == 0) refreshHealth()
                delay(200)
            }
        }
        recoverInterruptedSession()
    }

    fun setDeviceSn(sn: String) { deviceSn = sn }

    fun applyConfig(config: AppConfig) {
        val fromCloud = TimerPackage.fromProducts(config.products)
        val packages = fromCloud.ifEmpty {
            // The org's published config has no TIMER products yet (CMP can't
            // create them yet either) -- fall back to this app's bundled
            // defaults rather than show an empty, unsellable screen.
            Log.w(TAG, "No TIMER products in org config, using bundled defaults")
            TimerPackage.fromProducts(loadBundledConfig().products)
        }
        _state.update {
            it.copy(
                packages = packages,
                title = config.branding.brand_name.takeUnless { n -> n.isBlank() || n == "GS-SSP" } ?: "Self-Service",
                subtitle = config.branding.welcome_message,
            )
        }
        refreshHealth()
    }

    fun setLocked(locked: Boolean) = _state.update { it.copy(locked = locked) }

    fun setDemoMode(on: Boolean) {
        if (_state.value.screen !is Screen.Home) return
        _state.update { it.copy(demoMode = on) }
    }

    val canEnterTechMode: Boolean get() = _state.value.screen is Screen.Home

    // ---- customer flow -------------------------------------------------

    fun select(pkg: TimerPackage) {
        val s = _state.value
        if (s.screen !is Screen.Home || s.locked || !attached) return
        returnHomeJob?.cancel()
        if (s.demoMode) {
            _state.update { it.copy(screen = Screen.Paying(pkg, "DEMO — no card needed")) }
            viewModelScope.launch {
                delay(1800)
                onPaid(pkg, ecrRefNum = "DEMO_${System.currentTimeMillis()}", bankRef = "", entryMode = "DEMO")
            }
            return
        }

        val ecrRefNum = "TIMER_${System.currentTimeMillis()}"
        _state.update { it.copy(screen = Screen.Paying(pkg, "Tap, insert or swipe your card")) }
        TtsManager.speak("Please present your card")
        viewModelScope.launch {
            // PENDING before the bank call, same as wash: a crash between
            // approval and our own write must never leave an untracked charge.
            TransactionRepository.recordTransaction(
                getApplication(),
                TransactionRecord(
                    device_sn = deviceSn,
                    amount = pkg.priceCents,
                    payment_status = "PENDING",
                    ecr_ref_num = ecrRefNum,
                    payment_method = "CREDIT_CARD",
                    product_id = pkg.productId,
                )
            )
            payment.startSale(pkg.priceCents, ecrRefNum, object : IPaymentProvider.PaymentCallback {
                override fun onCardInfo(info: IPaymentProvider.CardInfo) { pendingCardInfo = info }

                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    // Deliberately ignores whether the customer hit Cancel in
                    // the meantime: money moved, so they get their time.
                    viewModelScope.launch { onPaid(pkg, ecrRefNum, refNum, entryMode) }
                }

                override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                    viewModelScope.launch {
                        TransactionRepository.updatePaymentStatus(getApplication(), ecrRefNum, "DECLINED")
                    }
                    if (isHardwareFault) {
                        DiagnosticManager.reportError(deviceSn, "CARD_READER_FAULT", severity = "CRITICAL", trace = errorMsg)
                    }
                    Log.w(TAG, "Sale $ecrRefNum not completed: $errorMsg (hardwareFault=$isHardwareFault)")
                    if (_state.value.screen is Screen.Paying) {
                        val reason = DeclineReason.classify(errorMsg, isHardwareFault)
                        showThenHome(Screen.Declined(reason), if (reason == DeclineReason.UNAVAILABLE) 8000 else 4000)
                    }
                }

                override fun onProgress(message: String) {
                    val cur = _state.value.screen
                    if (cur is Screen.Paying && message.isNotBlank()) {
                        _state.update { it.copy(screen = cur.copy(message = message)) }
                    }
                }
            })
        }
    }

    fun cancelPayment() {
        if (_state.value.screen !is Screen.Paying) return
        if (_state.value.demoMode) {
            _state.update { it.copy(screen = Screen.Home) }
            return
        }
        // The provider reports the cancellation through onFailure, which
        // returns us home; onSuccess can still win a race and start the session.
        runCatching { payment.cancelCurrentTransaction() }
            .onFailure { Log.w(TAG, "cancelCurrentTransaction failed: ${it.message}") }
    }

    private suspend fun onPaid(pkg: TimerPackage, ecrRefNum: String, bankRef: String, entryMode: String) {
        val demo = _state.value.demoMode
        if (!demo) {
            val card = pendingCardInfo.also { pendingCardInfo = null }
            TransactionRepository.updatePaymentStatus(
                getApplication(), ecrRefNum, "PAID", entryMode,
                paymentMethod = card?.let { CardTypeClassifier.paymentMethod(it.scheme, it.aid) },
                cardAid = card?.aid, cardBin = card?.bin, cardBrand = card?.brand,
            )
        }
        // The countdown runs from when the hold was issued, not from after the
        // confirm window / cloud write -- measured on a Q3mini, starting it
        // late left the screen showing 0:02 after the relay had already dropped.
        val issuedAt = SystemClock.elapsedRealtime()
        if (startOutput(pkg.durationMs, ecrRefNum, pkg.productId)) {
            runSession(pkg, pkg.durationMs - (SystemClock.elapsedRealtime() - issuedAt), pkg.durationMs)
            TtsManager.speak("Payment approved. Your ${pkg.name.lowercase()} is on.")
            if (!demo) TransactionRepository.updateHardwareStatus(getApplication(), ecrRefNum, "COMMAND_SENT_UNCONFIRMED")
        } else {
            onStartFailed(pkg, ecrRefNum, bankRef, demo)
        }
    }

    /**
     * Persists the session, then turns the output on. Some vendors' hold call
     * blocks for the whole duration (see IGpioProvider.holdRelayOutput), so it
     * runs on its own IO coroutine: a false within the first moments is a real
     * rejection; still running after that means the board accepted it.
     */
    private suspend fun startOutput(durationMs: Long, ecrRefNum: String, productId: String): Boolean {
        store.save(SessionStore.Active(ecrRefNum, productId, System.currentTimeMillis() + durationMs, durationMs))
        val out = output
        holdRejected = false
        val hold = viewModelScope.launch(Dispatchers.IO) {
            val ok = out.start(durationMs)
            if (!ok) holdRejected = true
        }
        withTimeoutOrNull(START_CONFIRM_WINDOW_MS) { hold.join() }
        if (holdRejected) {
            store.clear()
            return false
        }
        return true
    }

    @Volatile private var holdRejected = false

    private fun runSession(pkg: TimerPackage, remainingMs: Long, totalMs: Long) {
        val startedAt = SystemClock.elapsedRealtime() - (totalMs - remainingMs)
        _state.update { it.copy(screen = Screen.Running(pkg, startedAt, totalMs)) }
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            var warned = remainingMs <= WARN_BEFORE_END_MS
            while (isActive) {
                val left = totalMs - (SystemClock.elapsedRealtime() - startedAt)
                if (left <= 0) break
                if (!warned && left <= WARN_BEFORE_END_MS) {
                    warned = true
                    TtsManager.speak("Thirty seconds remaining")
                }
                delay(250)
            }
            // Second safeguard behind the hardware timer.
            withContext(Dispatchers.IO) { output.stop() }
            store.clear()
            TtsManager.speak("Time is up. Thank you!")
            showThenHome(Screen.Finished(pkg), 5000)
        }
    }

    private suspend fun onStartFailed(pkg: TimerPackage, ecrRefNum: String, bankRef: String, demo: Boolean) {
        _state.update { it.copy(screen = Screen.StartFailed(refunded = null)) }
        TtsManager.speak("Sorry, the machine could not start. Your payment is being reversed.")
        if (demo) {
            showThenHome(Screen.StartFailed(refunded = true), 6000)
            return
        }
        DiagnosticManager.reportError(deviceSn, "TIMER_OUTPUT_START_FAIL", severity = "CRITICAL", trace = "product=${pkg.productId}")
        TransactionRepository.updateHardwareStatus(getApplication(), ecrRefNum, "HARDWARE_ERROR")
        if (bankRef.isEmpty()) {
            showThenHome(Screen.StartFailed(refunded = false), 8000)
            return
        }
        payment.voidOrRefund(bankRef, pkg.priceCents) { success, method ->
            viewModelScope.launch {
                if (success) {
                    TransactionRepository.updatePaymentStatus(getApplication(), ecrRefNum, if (method == "REFUND") "REFUNDED" else "VOIDED")
                } else {
                    DiagnosticManager.reportError(deviceSn, "VOID_AND_REFUND_FAILED", severity = "CRITICAL", trace = ecrRefNum)
                }
                showThenHome(Screen.StartFailed(refunded = success), 8000)
            }
        }
    }

    /**
     * App died mid-session (crash, watchdog, reboot). Always force the output
     * off first -- never trust a hold we no longer own -- then give back any
     * time the customer already paid for.
     */
    private fun recoverInterruptedSession() {
        val saved = store.load() ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { realOutput.stop() }
            val remaining = saved.endAtWallMs - System.currentTimeMillis()
            if (remaining < MIN_RESUME_MS || saved.ecrRefNum.startsWith("DEMO_")) {
                store.clear()
                return@launch
            }
            DiagnosticManager.reportError(deviceSn, "TIMER_SESSION_RESUMED", severity = "WARNING", trace = "${saved.ecrRefNum} remaining=${remaining}ms")
            // Runs before the org config has loaded, so fall back to the bundled
            // packages for the display name.
            val known = _state.value.packages.ifEmpty { TimerPackage.fromProducts(loadBundledConfig().products) }
            val pkg = known.firstOrNull { it.productId == saved.productId }
                ?: TimerPackage(saved.productId, "Session", 0, (saved.totalMs / 1000).toInt())
            val out = realOutput
            store.save(saved)
            launch(Dispatchers.IO) { out.start(remaining) }
            runSession(pkg, remaining, saved.totalMs)
        }
    }

    private fun showThenHome(screen: Screen, afterMs: Long) {
        _state.update { it.copy(screen = screen) }
        returnHomeJob?.cancel()
        returnHomeJob = viewModelScope.launch {
            delay(afterMs)
            _state.update { it.copy(screen = Screen.Home) }
            refreshHealth()
        }
    }

    private fun refreshHealth() {
        val hwOk = runCatching { hardwareOk() }.getOrDefault(false)
        val configOk = ConfigManager.getConfig() != null
        val ok = _state.value.demoMode || (hwOk && configOk)
        if (ok != _state.value.healthy) Log.w(TAG, "health -> $ok (hardware=$hwOk config=$configOk)")
        _state.update { it.copy(healthy = ok, locked = DeviceAccessManager.isLocked()) }
    }

    private fun loadBundledConfig(): AppConfig = runCatching {
        val text = getApplication<Application>().assets.open("default_config.json").bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true; coerceInputValues = true }.decodeFromString(AppConfig.serializer(), text)
    }.getOrElse {
        Log.e(TAG, "Bundled config unreadable: ${it.message}")
        AppConfig(version = "0")
    }

    override fun onCleared() {
        // Activity finishing for good: never leave the output latched.
        if (attached && _state.value.screen is Screen.Running) realOutput.stop()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AegisTimer"
        private const val WARN_BEFORE_END_MS = 30_000L
        private const val START_CONFIRM_WINDOW_MS = 1_500L
        private const val MIN_RESUME_MS = 5_000L
    }
}
