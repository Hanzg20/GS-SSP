package com.goldsky.ssp.feature.timer

import com.goldsky.ssp.payment.hardware.DeclineReason
import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.common.TtsManager
import com.goldsky.ssp.model.AppConfig
import com.goldsky.ssp.model.PaymentMethodMode
import com.goldsky.ssp.payment.CardTypeClassifier
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DeviceAccessManager
import com.goldsky.ssp.payment.DiagnosticManager
import com.goldsky.ssp.payment.TransactionRecord
import com.goldsky.ssp.payment.CouponPeekResult
import com.goldsky.ssp.payment.CouponRedeemResult
import com.goldsky.ssp.payment.CouponRepository
import com.goldsky.ssp.payment.TestSale
import com.goldsky.ssp.payment.VipDeductResult
import com.goldsky.ssp.payment.VipRepository
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
        /** [bay] is null on a single-bay terminal; [chargeCents] differs from the package price only for a technician test sale. */
        data class Paying(val pkg: TimerPackage, val message: String, val bay: Bay? = null, val chargeCents: Int = pkg.priceCents) : Screen
        /** Dual-bay: payment done, pulses accepted; back to Home (which shows the countdown) shortly. */
        data class BayStarted(val bay: Bay, val pkg: TimerPackage, val extended: Boolean) : Screen
        data class Running(val pkg: TimerPackage, val startedAt: Long, val totalMs: Long) : Screen
        data class Finished(val pkg: TimerPackage) : Screen
        /** Customer-facing outcome only; the raw provider message goes to the log and the transaction row. */
        data class Declined(val reason: DeclineReason) : Screen
        /** [refunded] null while the reversal is still in flight. */
        data class StartFailed(val refunded: Boolean?) : Screen
        /** A scanned VIP code / coupon is about to pay for [pkg]; [chargeCents] is what is left to pay by card (0 = nothing). */
        data class Confirm(val pkg: TimerPackage, val bay: Bay?, val pending: Pending, val chargeCents: Int) : Screen
        /** Waiting on the server (VIP deduction / coupon redemption). */
        data class Working(val message: String) : Screen
        /**
         * Wash's payment page: card, or scan a coupon / VIP code, for [pkg]. [notice] says
         * why the customer is back here (no code read, balance short, ...).
         */
        data class ChoosePay(val pkg: TimerPackage, val bay: Bay?, val notice: String? = null, val card: Boolean = true, val scan: Boolean = true) : Screen
        /** Paid: the machine is being started (coin pulses going out, shown as they go). */
        data class Starting(val pkg: TimerPackage, val bay: Bay?) : Screen
        /** The scanner is open for a code that pays for [pkg] (the camera page covers ours). */
        data class ScanToPay(val pkg: TimerPackage, val bay: Bay?) : Screen
    }

    /**
     * A code scanned on the payment page, about to pay on Confirm (same two kinds as
     * wash: a 6-character VIP member code, or a coupon).
     */
    sealed interface Pending {
        data class Vip(val cardUid: String, val memberCode: String, val balanceCents: Int) : Pending
        data class Coupon(val code: String, val type: String, val value: Int, val applicableProductId: String?, val expiresAt: String?) : Pending
    }

    data class UiState(
        val screen: Screen = Screen.Home,
        val packages: List<TimerPackage> = emptyList(),
        val title: String = "Self-Service",
        // False when the merchant set no brand name (title is the fallback): the header
        // then gives the announcement both lines' height.
        val titleSet: Boolean = false,
        val subtitle: String? = null,
        val locked: Boolean = false,
        val healthy: Boolean = true,
        val demoMode: Boolean = false,
        val now: Long = SystemClock.elapsedRealtime(),
        /** Dual-bay terminal (technician setting): Home shows one column per numbered unit. */
        val dual: Boolean = false,
        /** Dual-bay: the sides currently running. */
        val bays: Map<Bay, BayRun> = emptyMap(),
        /** Pulse trains in progress, by unit code ("" on a single-unit terminal). */
        val pulses: Map<String, PulseTrain> = emptyMap(),
        /** Short customer message (scan result, code not valid here, ...). */
        val notice: String? = null,
    )

    /**
     * A running side. The machine's own timer board does the timing (coin
     * pulses); this only drives the on-screen countdown.
     * [endsAt] is SystemClock.elapsedRealtime().
     */
    data class BayRun(val endsAt: Long, val totalMs: Long) {
        fun remaining(now: Long) = (endsAt - now).coerceAtLeast(0)
    }

    /** Coin pulses being sent to a unit right now: [sent] of [total]. */
    data class PulseTrain(val sent: Int, val total: Int)

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
                titleSet = config.branding.brand_name.let { n -> n.isNotBlank() && n != "GS-SSP" },
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
        // Same as Wash: card or scan on a payment page, unless the operator allows only one
        // (KioskSettings.payment_method_mode); anything else offers both.
        when (ConfigManager.getConfig()?.settings?.payment_method_mode ?: PaymentMethodMode.ALL) {
            PaymentMethodMode.CARD_ONLY -> payByCard(pkg, side)
            PaymentMethodMode.SCAN_ONLY -> {
                _state.update { it.copy(screen = Screen.ScanToPay(pkg, side)) }
                TtsManager.speakRepeated(str(com.goldsky.ssp.core.ui.R.string.tts_scan_code_prompt))
                scanRequests.tryEmit(Unit)
            }
            else -> showChoosePay(pkg, side, null)
        }
    }

    private fun showChoosePay(pkg: TimerPackage, bay: Bay?, notice: String?) {
        val mode = ConfigManager.getConfig()?.settings?.payment_method_mode ?: PaymentMethodMode.ALL
        _state.update {
            it.copy(screen = Screen.ChoosePay(pkg, bay, notice, card = mode != PaymentMethodMode.SCAN_ONLY, scan = mode != PaymentMethodMode.CARD_ONLY))
        }
        TtsManager.speakRepeated(notice ?: str(com.goldsky.ssp.core.ui.R.string.tts_choose_payment))
        // Nobody chose: back to Home, like any other page left alone.
        returnHomeJob?.cancel()
        returnHomeJob = viewModelScope.launch {
            delay(CHOICE_MS)
            if (_state.value.screen is Screen.ChoosePay) { TtsManager.stop(); _state.update { it.copy(screen = Screen.Home) } }
        }
    }

    private fun str(id: Int) = getApplication<Application>().getString(id)

    /** Payment page: "Pay by Card". */
    fun choiceCard() {
        val c = _state.value.screen as? Screen.ChoosePay ?: return
        returnHomeJob?.cancel()
        TtsManager.stop()
        payByCard(c.pkg, c.bay)
    }

    /** Payment page: "Scan Coupon / VIP". MainActivity opens the scanner on [scanRequests]. */
    fun choiceScan() {
        val c = _state.value.screen as? Screen.ChoosePay ?: return
        returnHomeJob?.cancel()
        TtsManager.stop()
        _state.update { it.copy(screen = Screen.ScanToPay(c.pkg, c.bay)) }
        TtsManager.speakRepeated(str(com.goldsky.ssp.core.ui.R.string.tts_scan_code_prompt))
        scanRequests.tryEmit(Unit)
    }

    /** Payment page "Cancel": nothing was charged. */
    fun choiceCancel() {
        val s = _state.value.screen
        if (s !is Screen.ChoosePay && s !is Screen.ScanToPay) return
        returnHomeJob?.cancel()
        TtsManager.stop()
        _state.update { it.copy(screen = Screen.Home) }
    }

    /** Opening the scanner for a payment code: MainActivity collects this. */
    val scanRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * The scan ended without a code (timeout, camera unavailable, customer backed out):
     * back to the payment page with the reason, so the card is one tap away.
     */
    fun scanEnded(unavailable: Boolean, quiet: Boolean = false) {
        val s = _state.value.screen
        if (s is Screen.ScanToPay) {
            TtsManager.stop()
            // [quiet]: the customer pressed Back -- nothing went wrong, no reason to give.
            showChoosePay(s.pkg, s.bay, if (quiet) null else str(if (unavailable) com.goldsky.ssp.core.ui.R.string.pay_scan_unavailable else com.goldsky.ssp.core.ui.R.string.pay_scan_timeout))
        } else if (unavailable) {
            notice(str(com.goldsky.ssp.core.ui.R.string.toast_scanner_unavailable))
        }
    }

    private fun payByCard(pkg: TimerPackage, side: Bay?) {
        if (_state.value.demoMode) {
            _state.update { it.copy(screen = Screen.Paying(pkg, "DEMO — no card needed", side)) }
            viewModelScope.launch {
                delay(1800)
                onPaid(pkg, ecrRefNum = "DEMO_${System.currentTimeMillis()}", bankRef = "", entryMode = "DEMO", bay = side)
            }
            return
        }

        cardSale(pkg, side, pkg.priceCents)
    }

    /**
     * A code scanned on the payment page for [ScanToPay.pkg]: a VIP code or a coupon goes
     * to the same Confirm page as a Home scan (nothing is spent before Confirm); anything
     * that can't pay for this package brings the customer back with the reason.
     */
    private suspend fun payScanned(code: String, sc: Screen.ScanToPay) {
        val back = { why: String -> if (_state.value.screen == sc) showChoosePay(sc.pkg, sc.bay, why) }
        _state.update { it.copy(screen = Screen.Working("Checking your code…")) }
        if (Regex("^[A-Za-z0-9]{6}$").matches(code)) {
            val uid = VipRepository.resolveCardUidByQrCode(code)
            val card = uid?.let { VipRepository.getVipCard(it) }
            _state.update { it.copy(screen = sc) }
            when {
                uid == null || card == null -> back("Member code not recognized.")
                !card.is_active -> back("This VIP card is deactivated.")
                card.balance_cents < sc.pkg.priceCents -> back("Insufficient VIP balance: ${dollars(card.balance_cents)}. Please pay by card.")
                else -> {
                    _state.update { it.copy(screen = Screen.Confirm(sc.pkg, sc.bay, Pending.Vip(uid, code.uppercase(), card.balance_cents), 0)) }
                    TtsManager.speakRepeated("Pay ${dollars(sc.pkg.priceCents)} from your VIP balance? Tap Confirm.")
                }
            }
            return
        }
        val peek = CouponRepository.peekCoupon(code, deviceSn)
        _state.update { it.copy(screen = sc) }
        when {
            peek !is CouponPeekResult.Success -> back("This code can't be used.")
            peek.applicableProductId != null && peek.applicableProductId != sc.pkg.productId -> {
                val target = _state.value.packages.firstOrNull { it.productId == peek.applicableProductId }
                back(if (target != null) "This coupon is for the ${formatPrice(target.priceCents)} package." else "This coupon can't be used here.")
            }
            else -> {
                val coupon = Pending.Coupon(code, peek.type, peek.value, peek.applicableProductId, peek.expiresAt)
                val charge = couponPrice(sc.pkg.priceCents, coupon)
                _state.update { it.copy(screen = Screen.Confirm(sc.pkg, sc.bay, coupon, charge)) }
                TtsManager.speakRepeated(
                    if (charge <= 0) "Coupon accepted. Tap Confirm to start." else "Coupon accepted. Tap Confirm, then pay ${dollars(charge)} by card."
                )
            }
        }
    }

    /**
     * Card payment of [amountCents] for [pkg] (the package price, or what a
     * coupon leaves to pay). [afterApproved] runs once the card is approved,
     * before the machine starts -- a discount coupon is consumed there, so a
     * declined card never uses it up. Time / pulses always follow [pkg].
     */
    private fun cardSale(pkg: TimerPackage, side: Bay?, amountCents: Int, afterApproved: (suspend () -> Unit)? = null) {
        // Technician small real test (TestSale): charge the armed amount, run
        // the full package -- pulses/time below follow pkg, not the charge.
        val testCents = TestSale.consume()
        val chargeCents = testCents ?: amountCents
        val ecrRefNum = (if (testCents != null) TestSale.REF_PREFIX else "TIMER_") + System.currentTimeMillis()
        _state.update { it.copy(screen = Screen.Paying(pkg, "Tap, insert or swipe your card", side, chargeCents)) }
        TtsManager.speakRepeated("Please tap, insert or swipe your card.")
        viewModelScope.launch {
            // PENDING before the bank call, same as wash: a crash between
            // approval and our own write must never leave an untracked charge.
            TransactionRepository.recordTransaction(
                getApplication(),
                TransactionRecord(
                    device_sn = deviceSn,
                    amount = chargeCents,
                    payment_status = "PENDING",
                    ecr_ref_num = ecrRefNum,
                    payment_method = "CREDIT_CARD",
                    product_id = pkg.productId,
                    service_bay = side?.code,
                )
            )
            var cancelled = false
            payment.startSale(chargeCents, ecrRefNum, object : IPaymentProvider.PaymentCallback {
                override fun onCardInfo(info: IPaymentProvider.CardInfo) { pendingCardInfo = info }
                override fun onCancelled() { cancelled = true }

                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    // Deliberately ignores whether the customer hit Cancel in
                    // the meantime: money moved, so they get their time.
                    viewModelScope.launch {
                        afterApproved?.invoke()
                        onPaid(pkg, ecrRefNum, refNum, entryMode, side, chargeCents, authCode)
                    }
                }

                override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                    viewModelScope.launch {
                        val card = pendingCardInfo.also { pendingCardInfo = null }
                        TransactionRepository.recordFailedCardSale(getApplication(), ecrRefNum, card, cancelled)
                    }
                    if (isHardwareFault) {
                        DiagnosticManager.reportError(deviceSn, "CARD_READER_FAULT", severity = "CRITICAL", trace = errorMsg)
                    }
                    Log.w(TAG, "Sale $ecrRefNum not completed: $errorMsg (hardwareFault=$isHardwareFault)")
                    if (_state.value.screen is Screen.Paying) {
                        val reason = DeclineReason.classify(errorMsg, isHardwareFault)
                        showThenHome(Screen.Declined(reason), if (reason == DeclineReason.UNAVAILABLE) 8000 else 4000)
                        TtsManager.speakRepeated(
                            when (reason) {
                                DeclineReason.CANCELLED -> "Payment cancelled. No charge was made."
                                DeclineReason.UNAVAILABLE -> "Card payment is unavailable. Please contact the attendant."
                                DeclineReason.DECLINED -> "Payment not completed. No charge was made."
                            }
                        )
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

    // ---- scanned VIP code / coupon ---------------------------------------------

    private var noticeJob: Job? = null

    private fun dollars(cents: Int) = "$" + String.format(java.util.Locale.US, "%.2f", cents / 100.0)

    /**
     * A code from the scanner, opened by the payment page's "Scan Coupon / VIP" (Home has
     * no scan button since 2026-10-26, owner). 6 letters/digits = VIP member code, anything
     * else = coupon -- the same routing wash uses.
     */
    fun onScanned(raw: String) {
        val code = raw.trim()
        val sc = _state.value.screen as? Screen.ScanToPay ?: return
        if (code.isEmpty()) return
        TtsManager.stop()
        viewModelScope.launch { payScanned(code, sc) }
    }

    fun cancelConfirm() {
        if (_state.value.screen !is Screen.Confirm) return
        TtsManager.stop()
        _state.update { it.copy(screen = Screen.Home) }
    }

    /** The customer confirmed the Confirm screen. */
    fun confirm() {
        val c = _state.value.screen as? Screen.Confirm ?: return
        TtsManager.stop()
        when (val pending = c.pending) {
            is Pending.Vip -> payWithVip(c.pkg, c.bay, pending)
            is Pending.Coupon ->
                if (c.chargeCents <= 0) redeemFree(c.pkg, c.bay, pending)
                else cardSale(c.pkg, c.bay, c.chargeCents) {
                    // Paid by card already: use the coupon up now. A lost race
                    // (used elsewhere meanwhile) still runs -- they paid.
                    if (CouponRepository.redeemCoupon(pending.code, deviceSn) !is CouponRedeemResult.Success) {
                        DiagnosticManager.reportError(deviceSn, "COUPON_REDEEM_AFTER_PAY_FAILED", severity = "WARNING", trace = "code=${pending.code}")
                    }
                }
        }
    }

    private fun payWithVip(pkg: TimerPackage, bay: Bay?, vip: Pending.Vip) {
        _state.update { it.copy(screen = Screen.Working("Paying from your VIP balance…")) }
        viewModelScope.launch {
            when (val r = VipRepository.deductBalance(vip.cardUid, pkg.priceCents)) {
                is VipDeductResult.Success -> {
                    val ref = "VIP_${vip.cardUid}_${System.currentTimeMillis()}"
                    TransactionRepository.recordTransaction(
                        getApplication(),
                        TransactionRecord(
                            device_sn = deviceSn, amount = pkg.priceCents, payment_status = "PAID", ecr_ref_num = ref,
                            payment_method = "VIP_CARD", product_id = pkg.productId, service_bay = bay?.code,
                            vip_card_uid = vip.cardUid, entry_mode = "QR_SCAN",
                        ),
                    )
                    startPaidService(pkg, bay, ref, "VIP balance", "Paid from your VIP balance.")
                }
                is VipDeductResult.Rejected -> {
                    val msg = when (r.reason) {
                        "insufficient_balance" -> "Not enough VIP balance (${formatPrice(vip.balanceCents)}). Please pay by card."
                        "daily_limit_exceeded" -> "This card's daily limit is reached."
                        // Also a card of another organization (deduct_vip_balance is org-scoped).
                        else -> "This VIP card can't be used here."
                    }
                    backToPayment(pkg, bay, msg)
                }
                else -> backToPayment(pkg, bay, "Network problem. Please try again.")
            }
        }
    }

    private fun redeemFree(pkg: TimerPackage, bay: Bay?, coupon: Pending.Coupon) {
        _state.update { it.copy(screen = Screen.Working("Applying your coupon…")) }
        viewModelScope.launch {
            when (CouponRepository.redeemCoupon(coupon.code, deviceSn)) {
                is CouponRedeemResult.Success -> {
                    val ref = "COUPON_${System.currentTimeMillis()}"
                    TransactionRepository.recordTransaction(
                        getApplication(),
                        TransactionRecord(
                            device_sn = deviceSn, amount = 0, payment_status = "PAID", ecr_ref_num = ref,
                            payment_method = "COUPON", product_id = pkg.productId, service_bay = bay?.code, entry_mode = "QR_SCAN",
                        ),
                    )
                    startPaidService(pkg, bay, ref, "coupon ${coupon.code}", "Your coupon covers it.")
                }
                else -> backToPayment(pkg, bay, "This coupon can't be used.")
            }
        }
    }

    /**
     * Starts [pkg] after a VIP / free-coupon payment (already recorded PAID).
     * Neither can be reversed from the terminal, so a machine that won't start
     * raises a CRITICAL alert for staff to compensate in CMP.
     */
    private suspend fun startPaidService(pkg: TimerPackage, bay: Bay?, ref: String, paidWith: String, spoken: String) {
        val started = if (bay != null) {
            val extended = _state.value.bays.containsKey(bay)
            startBay(bay, pkg, ref).also { ok -> if (ok) showThenHome(Screen.BayStarted(bay, pkg, extended), BAY_STARTED_MS) }
        } else {
            val issuedAt = SystemClock.elapsedRealtime()
            startOutput(pkg.durationMs, pkg.priceCents, ref, pkg.productId).also { ok ->
                if (ok) runSession(pkg, pkg.durationMs - (SystemClock.elapsedRealtime() - issuedAt), pkg.durationMs)
            }
        }
        if (started) {
            TransactionRepository.updateHardwareStatus(getApplication(), ref, "ACK_RECEIVED")
            TtsManager.speakRepeated(if (bay != null) "$spoken ${pkg.name} ${bay.number} is on." else "$spoken Your ${pkg.name.lowercase()} is on.")
        } else {
            TransactionRepository.updateHardwareStatus(getApplication(), ref, "HARDWARE_ERROR")
            DiagnosticManager.reportError(
                deviceSn, "TIMER_PREPAID_NOT_STARTED", severity = "CRITICAL",
                trace = "$ref paid with $paidWith (${pkg.priceCents}c) but the machine did not start -- compensate in CMP (VIP top-up / new coupon)",
            )
            showThenHome(Screen.StartFailed(refunded = false), 8000)
        }
    }

    /**
     * A VIP balance / coupon that couldn't pay after Confirm: back to the payment page with
     * the reason, the card one tap away (Wash does the same) -- not a 5 s line on Home.
     */
    private fun backToPayment(pkg: TimerPackage, bay: Bay?, why: String) = showChoosePay(pkg, bay, why)

    private fun notice(text: String) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = text) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _state.update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    /** Same formula as wash (docs/coupon_redemption_integration.md §3.2): never below 0. */
    private fun couponPrice(priceCents: Int, c: Pending.Coupon): Int = when (c.type) {
        "PERCENT_OFF" -> priceCents - (priceCents * c.value / 100)
        "FIXED_OFF" -> maxOf(0, priceCents - c.value)
        "FREE_WASH" -> 0
        else -> priceCents
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

    private suspend fun onPaid(
        pkg: TimerPackage, ecrRefNum: String, bankRef: String, entryMode: String,
        bay: Bay? = null, chargeCents: Int = pkg.priceCents, authCode: String? = null,
    ) {
        val demo = _state.value.demoMode
        if (!demo) {
            val card = pendingCardInfo.also { pendingCardInfo = null }
            TransactionRepository.updatePaymentStatus(
                getApplication(), ecrRefNum, "PAID", entryMode,
                paymentMethod = card?.let { CardTypeClassifier.paymentMethod(it.scheme, it.aid, it.brand) },
                cardAid = card?.aid, cardBin = card?.bin, cardBrand = card?.brand,
                authCode = authCode,
            )
        }
        if (bay != null) {
            val extended = _state.value.bays.containsKey(bay)
            if (startBay(bay, pkg, ecrRefNum)) {
                if (!demo) TransactionRepository.updateHardwareStatus(getApplication(), ecrRefNum, "ACK_RECEIVED")
                showThenHome(Screen.BayStarted(bay, pkg, extended), BAY_STARTED_MS)
                TtsManager.speakRepeated(
                    if (extended) "Payment approved. Time added to ${pkg.name} ${bay.number}."
                    else "Payment approved. ${pkg.name} ${bay.number} is on."
                )
            } else {
                onStartFailed(pkg, ecrRefNum, bankRef, demo, chargeCents)
            }
            return
        }
        // The countdown runs from when the hold was issued, not from after the
        // confirm window / cloud write -- measured on a Q3mini, starting it
        // late left the screen showing 0:02 after the relay had already dropped.
        val issuedAt = SystemClock.elapsedRealtime()
        if (startOutput(pkg.durationMs, pkg.priceCents, ecrRefNum, pkg.productId)) {
            runSession(pkg, pkg.durationMs - (SystemClock.elapsedRealtime() - issuedAt), pkg.durationMs)
            TtsManager.speakRepeated("Payment approved. Your ${pkg.name.lowercase()} is on.")
            // startOutput only returns true once the board accepted the hold
            // (not rejected within the confirm window) -- the same evidence
            // wash's DigitIo "Confirmed" rests on. COMMAND_SENT_UNCONFIRMED
            // here made CMP flag every vacuum sale "ACK Missing (needs
            // compensation)".
            if (!demo) TransactionRepository.updateHardwareStatus(getApplication(), ecrRefNum, "ACK_RECEIVED")
        } else {
            onStartFailed(pkg, ecrRefNum, bankRef, demo, chargeCents)
        }
    }

    /**
     * Persists the session, then turns the output on. Some vendors' hold call
     * blocks for the whole duration (see IGpioProvider.holdRelayOutput), so it
     * runs on its own IO coroutine: a false within the first moments is a real
     * rejection; still running after that means the board accepted it.
     */
    private suspend fun startOutput(durationMs: Long, priceCents: Int, ecrRefNum: String, productId: String): Boolean {
        _state.value.packages.find { it.productId == productId }?.let { pkg -> _state.update { it.copy(screen = Screen.Starting(pkg, null)) } }
        store.save(SessionStore.Active(ecrRefNum, productId, System.currentTimeMillis() + durationMs, durationMs))
        val out = output
        holdRejected = false
        var confirmed = false
        val hold = viewModelScope.launch(Dispatchers.IO) {
            val ok = try { out.start(durationMs, priceCents) { sent, total -> showPulses("", sent, total) } } finally { endPulses("") }
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
                    TtsManager.speakRepeated("Thirty seconds left. Please finish up.")
                }
                delay(250)
            }
            // Second safeguard behind the hardware timer.
            withContext(Dispatchers.IO) { output.stop() }
            store.clear()
            TtsManager.speakRepeated("Time is up. Thank you!")
            showThenHome(Screen.Finished(pkg), 5000)
        }
    }

    /** [chargeCents]: what the card was actually charged -- the reversal must match it (a test sale charges less than the package). */
    private suspend fun onStartFailed(pkg: TimerPackage, ecrRefNum: String, bankRef: String, demo: Boolean, chargeCents: Int = pkg.priceCents) {
        val autoReverse = com.goldsky.ssp.payment.RefundPolicy.AUTO_REVERSAL
        _state.update { it.copy(screen = Screen.StartFailed(refunded = if (autoReverse) null else false)) }
        TtsManager.speakRepeated(
            if (autoReverse) "Sorry, the machine could not start. Your payment is being reversed."
            else "Sorry, the machine could not start. Please contact the attendant."
        )
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
        if (!autoReverse) {
            // Stays PAID + HARDWARE_ERROR (CMP: compensation); a person refunds or compensates.
            com.goldsky.ssp.payment.RefundPolicy.reportManualRefund(deviceSn, ecrRefNum, chargeCents, "timer output did not start, product=${pkg.productId}")
            showThenHome(Screen.StartFailed(refunded = false), 8000)
            return
        }
        payment.voidOrRefund(bankRef, chargeCents) { success, method ->
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
        _state.update { it.copy(screen = Screen.Starting(pkg, bay)) }

        val out = bayOutput(bay)
        val rejected = AtomicBoolean(false)
        val confirmed = AtomicBoolean(false)
        val pulses = viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                out.start(pkg.durationMs, pkg.priceCents) { sent, total -> showPulses(bay.code, sent, total) }
            } finally {
                endPulses(bay.code)
            }
            if (!ok) {
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

    /** Pulse animation state for [key]. Called from the IO pulse coroutine. */
    private fun showPulses(key: String, sent: Int, total: Int) = _state.update {
        it.copy(pulses = it.pulses + (key to PulseTrain(sent, total)))
    }

    /**
     * The train is over: its last state (all lit, 0 left) stays a moment, so even a 1-pulse
     * train (gone in half a second) is seen, then the time countdown takes over.
     */
    private fun endPulses(key: String) {
        val last = _state.value.pulses[key] ?: return
        viewModelScope.launch {
            delay(PULSES_DONE_HOLD_MS)
            _state.update { if (it.pulses[key] == last) it.copy(pulses = it.pulses - key) else it }
        }
    }

    /** Clears sides whose time ran out. The machine stops itself; this is display only. */
    private fun expireBays() {
        val now = SystemClock.elapsedRealtime()
        val name = _state.value.packages.firstOrNull()?.name ?: "Number"
        // Last 30 s of a unit: say it once per run (time added after that re-arms it).
        _state.value.bays.forEach { (bay, run) ->
            val left = run.remaining(now)
            if (left in 1..WARN_BEFORE_END_MS) {
                if (warnedBays.add(bay)) TtsManager.speakRepeated("Thirty seconds left on $name ${bay.number}.")
            } else if (left > WARN_BEFORE_END_MS) {
                warnedBays.remove(bay)
            }
        }
        val done = _state.value.bays.filterValues { it.remaining(now) <= 0 }.keys
        if (done.isEmpty()) return
        done.forEach { bayStores.getValue(it).clear(); warnedBays.remove(it) }
        _state.update { it.copy(bays = it.bays - done) }
        done.forEach { TtsManager.speakRepeated("Time is up on $name ${it.number}. Thank you!") }
    }

    private val warnedBays = mutableSetOf<Bay>()

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
            // A prompt repeats only while its page is up (owner, 2026-10-26: "Vacuum 2 is
            // on" went on over Home); the sentence being said finishes.
            TtsManager.cancelRepeat()
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
        private const val PENDING_MS = 90_000L
        private const val CHOICE_MS = 60_000L
        private const val PULSES_DONE_HOLD_MS = 1_000L
        private const val NOTICE_MS = 5_000L
    }
}
