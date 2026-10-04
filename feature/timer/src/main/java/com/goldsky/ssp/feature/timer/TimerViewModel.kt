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
import java.util.concurrent.atomic.AtomicBoolean

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
        /** [bay] is null on a single-bay terminal. */
        data class Paying(val pkg: TimerPackage, val message: String, val bay: Bay? = null) : Screen
        /** Dual-bay: payment done, pulses accepted; back to Home (which shows the countdown) shortly. */
        data class BayStarted(val bay: Bay, val pkg: TimerPackage, val extended: Boolean) : Screen
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
        /** Dual-bay terminal (technician setting): Home shows one column per numbered unit. */
        val dual: Boolean = false,
        /** Dual-bay: the sides currently running. */
        val bays: Map<Bay, BayRun> = emptyMap(),
    )

    /**
     * A running side. The machine's own timer board does the timing (coin
     * pulses); this only drives the on-screen countdown.
     * [endsAt] is SystemClock.elapsedRealtime().
     */
    data class BayRun(val endsAt: Long, val totalMs: Long) {
        fun remaining(now: Long) = (endsAt - now).coerceAtLeast(0)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val store = SessionStore(app)
    private val bayStores = Bay.entries.associateWith { SessionStore(app, "timer_session_${it.code}") }
    private lateinit var payment: IPaymentProvider
    private lateinit var realOutput: TimerOutput
    private lateinit var realBayOutput: (Bay) -> TimerOutput
    private var deviceSn = ""
    private var hardwareOk: () -> Boolean = { true }
    private var attached = false

    private var pendingCardInfo: IPaymentProvider.CardInfo? = null
    private var sessionJob: Job? = null
    private var returnHomeJob: Job? = null

    private val output: TimerOutput get() = if (_state.value.demoMode) DemoOutput else realOutput
    private fun bayOutput(bay: Bay): TimerOutput = if (_state.value.demoMode) DemoOutput else realBayOutput(bay)

    /**
     * [dual] and [bayOutput] come from the technician's output settings;
     * changing dual/single restarts the app (see MainActivity), so they are
     * fixed for this ViewModel's life.
     */
    fun attach(
        payment: IPaymentProvider,
        output: TimerOutput,
        hardwareOk: () -> Boolean,
        dual: Boolean = false,
        bayOutput: (Bay) -> TimerOutput = { output },
    ) {
        this.payment = payment
        this.realOutput = output
        this.realBayOutput = bayOutput
        this.hardwareOk = hardwareOk
        if (attached) return
        attached = true
        _state.update { it.copy(dual = dual) }
        // UI clock + periodic health refresh.
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                _state.update { it.copy(now = SystemClock.elapsedRealtime()) }
                expireBays()
                if (tick++ % 25 == 0) refreshHealth()
                delay(200)
            }
        }
        if (dual) recoverBays() else recoverInterruptedSession()
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
        if (_state.value.screen !is Screen.Home || _state.value.bays.isNotEmpty()) return
        _state.update { it.copy(demoMode = on) }
    }

    val canEnterTechMode: Boolean get() = _state.value.screen is Screen.Home

    /** Idle ads only when nothing is on screen worth watching -- not over a dual-bay countdown. */
    val idleForAds: Boolean get() = _state.value.screen is Screen.Home && _state.value.bays.isEmpty()

    // ---- customer flow -------------------------------------------------

    /** [bay] is required on a dual-bay terminal and ignored on a single-bay one. */
    fun select(pkg: TimerPackage, bay: Bay? = null) {
        val s = _state.value
        if (s.screen !is Screen.Home || s.locked || !attached) return
        val side = if (s.dual) bay ?: return else null
        // Single-bay: Home is never shown mid-session. Dual-bay: buying for a
        // running side adds time (the timer board adds a coin's worth).
        returnHomeJob?.cancel()
        if (s.demoMode) {
            _state.update { it.copy(screen = Screen.Paying(pkg, "DEMO — no card needed", side)) }
            viewModelScope.launch {
                delay(1800)
                onPaid(pkg, ecrRefNum = "DEMO_${System.currentTimeMillis()}", bankRef = "", entryMode = "DEMO", bay = side)
            }
            return
        }

        val ecrRefNum = "TIMER_${System.currentTimeMillis()}"
        _state.update { it.copy(screen = Screen.Paying(pkg, "Tap, insert or swipe your card", side)) }
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
                    service_bay = side?.code,
                )
            )
            payment.startSale(pkg.priceCents, ecrRefNum, object : IPaymentProvider.PaymentCallback {
                override fun onCardInfo(info: IPaymentProvider.CardInfo) { pendingCardInfo = info }

                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    // Deliberately ignores whether the customer hit Cancel in
                    // the meantime: money moved, so they get their time.
                    viewModelScope.launch { onPaid(pkg, ecrRefNum, refNum, entryMode, side) }
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

    /**
     * CMP remote start: one free session of [productId] through the same hold
     * path a paid session uses. Refused unless the kiosk is idle on Home.
     */
    suspend fun startRemoteSession(productId: String?, commandId: String, bayCode: String? = null): Boolean {
        val s = _state.value
        if (s.screen !is Screen.Home || !attached) return false
        val pkg = s.packages.find { it.productId == productId } ?: return false
        if (s.dual) {
            // Which side must be explicit: guessing would run the wrong hose.
            val bay = Bay.fromCode(bayCode) ?: return false
            returnHomeJob?.cancel()
            if (!startBay(bay, pkg, "REMOTE_$commandId")) return false
            showThenHome(Screen.BayStarted(bay, pkg, extended = false), BAY_STARTED_MS)
            TtsManager.speak("${pkg.name} ${bay.number} started by the operator")
            return true
        }
        returnHomeJob?.cancel()
        val issuedAt = SystemClock.elapsedRealtime()
        if (!startOutput(pkg.durationMs, pkg.priceCents, "REMOTE_$commandId", pkg.productId)) return false
        runSession(pkg, pkg.durationMs - (SystemClock.elapsedRealtime() - issuedAt), pkg.durationMs)
        TtsManager.speak("Service started by the operator. Your ${pkg.name.lowercase()} is on.")
        return true
    }

    private suspend fun onPaid(pkg: TimerPackage, ecrRefNum: String, bankRef: String, entryMode: String, bay: Bay? = null) {
        val demo = _state.value.demoMode
        if (!demo) {
            val card = pendingCardInfo.also { pendingCardInfo = null }
            TransactionRepository.updatePaymentStatus(
                getApplication(), ecrRefNum, "PAID", entryMode,
                paymentMethod = card?.let { CardTypeClassifier.paymentMethod(it.scheme, it.aid) },
                cardAid = card?.aid, cardBin = card?.bin, cardBrand = card?.brand,
            )
        }
        if (bay != null) {
            val extended = _state.value.bays.containsKey(bay)
            if (startBay(bay, pkg, ecrRefNum)) {
                if (!demo) TransactionRepository.updateHardwareStatus(getApplication(), ecrRefNum, "ACK_RECEIVED")
                showThenHome(Screen.BayStarted(bay, pkg, extended), BAY_STARTED_MS)
                TtsManager.speak(
                    if (extended) "Payment approved. Time added to ${pkg.name} ${bay.number}."
                    else "Payment approved. ${pkg.name} ${bay.number} is on."
                )
            } else {
                onStartFailed(pkg, ecrRefNum, bankRef, demo)
            }
            return
        }
        // The countdown runs from when the hold was issued, not from after the
        // confirm window / cloud write -- measured on a Q3mini, starting it
        // late left the screen showing 0:02 after the relay had already dropped.
        val issuedAt = SystemClock.elapsedRealtime()
        if (startOutput(pkg.durationMs, pkg.priceCents, ecrRefNum, pkg.productId)) {
            runSession(pkg, pkg.durationMs - (SystemClock.elapsedRealtime() - issuedAt), pkg.durationMs)
            TtsManager.speak("Payment approved. Your ${pkg.name.lowercase()} is on.")
            // startOutput only returns true once the board accepted the hold
            // (not rejected within the confirm window) -- the same evidence
            // wash's DigitIo "Confirmed" rests on. COMMAND_SENT_UNCONFIRMED
            // here made CMP flag every vacuum sale "ACK Missing (needs
            // compensation)".
            if (!demo) TransactionRepository.updateHardwareStatus(getApplication(), ecrRefNum, "ACK_RECEIVED")
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
    private suspend fun startOutput(durationMs: Long, priceCents: Int, ecrRefNum: String, productId: String): Boolean {
        store.save(SessionStore.Active(ecrRefNum, productId, System.currentTimeMillis() + durationMs, durationMs))
        val out = output
        holdRejected = false
        var confirmed = false
        val hold = viewModelScope.launch(Dispatchers.IO) {
            val ok = out.start(durationMs, priceCents)
            if (!ok) {
                holdRejected = true
                // Failed after the confirm window (e.g. pulse 3 of 8 in coin
                // mode): the session already started, so someone must look.
                if (confirmed) DiagnosticManager.reportError(deviceSn, "TIMER_OUTPUT_PARTIAL", severity = "CRITICAL", trace = "$ecrRefNum $priceCents cents")
            }
        }
        withTimeoutOrNull(START_CONFIRM_WINDOW_MS) { hold.join() }
        confirmed = true
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
            holdRejected = false
            launch(Dispatchers.IO) { if (!out.resume(remaining)) holdRejected = true }
            runSession(pkg, remaining, saved.totalMs)
            // A crash can land before onPaid's status write, leaving a PAID row
            // with no hardware status (CMP: "no hardware record").
            delay(START_CONFIRM_WINDOW_MS)
            if (holdRejected) {
                DiagnosticManager.reportError(deviceSn, "TIMER_OUTPUT_START_FAIL", severity = "CRITICAL", trace = "resume ${saved.ecrRefNum}")
            } else {
                TransactionRepository.updateHardwareStatus(getApplication(), saved.ecrRefNum, "ACK_RECEIVED")
            }
        }
    }

    // ---- dual-bay -----------------------------------------------------------

    /**
     * Sends [pkg]'s coin pulses to [bay] and adds its time to that side's
     * countdown (a running side gets extended -- its timer board adds the
     * coins' worth). Same crash-safety order as [startOutput]: the side's
     * record is on disk before the pulses go out, and is put back as it was
     * if the board rejects them.
     */
    private suspend fun startBay(bay: Bay, pkg: TimerPackage, ecrRefNum: String): Boolean {
        val bayStore = bayStores.getValue(bay)
        val before = bayStore.load()
        val now = SystemClock.elapsedRealtime()
        val current = _state.value.bays[bay]
        val totalMs = (current?.remaining(now) ?: 0L) + pkg.durationMs
        bayStore.save(SessionStore.Active(ecrRefNum, pkg.productId, System.currentTimeMillis() + totalMs, totalMs))

        val out = bayOutput(bay)
        val rejected = AtomicBoolean(false)
        val confirmed = AtomicBoolean(false)
        val pulses = viewModelScope.launch(Dispatchers.IO) {
            if (!out.start(pkg.durationMs, pkg.priceCents)) {
                rejected.set(true)
                if (confirmed.get()) DiagnosticManager.reportError(deviceSn, "TIMER_OUTPUT_PARTIAL", severity = "CRITICAL", trace = "$ecrRefNum bay=${bay.code} ${pkg.priceCents} cents")
            }
        }
        withTimeoutOrNull(START_CONFIRM_WINDOW_MS) { pulses.join() }
        confirmed.set(true)
        if (rejected.get()) {
            if (before != null && current != null) bayStore.save(before) else bayStore.clear()
            DiagnosticManager.reportError(deviceSn, "TIMER_OUTPUT_START_FAIL", severity = "CRITICAL", trace = "$ecrRefNum bay=${bay.code}")
            return false
        }
        _state.update { it.copy(bays = it.bays + (bay to BayRun(now + totalMs, totalMs))) }
        return true
    }

    /** Clears sides whose time ran out. The machine stops itself; this is display only. */
    private fun expireBays() {
        val now = SystemClock.elapsedRealtime()
        val done = _state.value.bays.filterValues { it.remaining(now) <= 0 }.keys
        if (done.isEmpty()) return
        done.forEach { bayStores.getValue(it).clear() }
        _state.update { it.copy(bays = it.bays - done) }
        val name = _state.value.packages.firstOrNull()?.name ?: "Number"
        done.forEach { TtsManager.speak("Time is up on $name ${it.number}. Thank you!") }
    }

    /**
     * App restarted mid-session: put the countdowns back. Nothing is sent to
     * the board -- each side's timer board is already timing the coins it got,
     * and resending pulses would hand out free time.
     */
    private fun recoverBays() {
        val nowWall = System.currentTimeMillis()
        val now = SystemClock.elapsedRealtime()
        val restored = Bay.entries.mapNotNull { bay ->
            val saved = bayStores.getValue(bay).load() ?: return@mapNotNull null
            val remaining = saved.endAtWallMs - nowWall
            if (remaining <= 0) {
                bayStores.getValue(bay).clear()
                null
            } else {
                bay to BayRun(now + remaining, saved.totalMs)
            }
        }.toMap()
        if (restored.isNotEmpty()) _state.update { it.copy(bays = restored) }
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
        private const val BAY_STARTED_MS = 4_000L
    }
}
