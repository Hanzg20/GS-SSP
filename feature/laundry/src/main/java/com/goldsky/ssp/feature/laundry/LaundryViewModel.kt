package com.goldsky.ssp.feature.laundry

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.common.TtsManager
import com.goldsky.ssp.model.AppConfig
import com.goldsky.ssp.payment.CardTypeClassifier
import com.goldsky.ssp.payment.DiagnosticManager
import com.goldsky.ssp.payment.RefundPolicy
import com.goldsky.ssp.payment.TestSale
import com.goldsky.ssp.payment.CouponPeekResult
import com.goldsky.ssp.payment.CouponRedeemResult
import com.goldsky.ssp.payment.CouponRepository
import com.goldsky.ssp.payment.VipDeductResult
import com.goldsky.ssp.payment.VipRepository
import com.goldsky.ssp.payment.TransactionRecord
import com.goldsky.ssp.payment.TransactionRepository
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import com.goldsky.ssp.payment.hardware.modbus.ModbusRelayBoard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Starts machines: the Modbus relay module, or a stand-in for demo mode. */
interface MachineStarter {
    /** True when the module answers (checked before a card is charged). */
    suspend fun ready(m: LaundryMachineConfig): Boolean
    suspend fun start(m: LaundryMachineConfig, pulses: Int): ModbusRelayBoard.PulseResult
    /** The module's inputs, for machines wired with a "running" signal; null = unknown. */
    suspend fun inputs(): BooleanArray?
    /** Technician: is the module there (switches channel 1 off, which changes nothing). */
    suspend fun ping(): Boolean
    /** Technician: one test pulse on [relay]. */
    suspend fun testPulse(relay: Int): ModbusRelayBoard.PulseResult
}

class ModbusStarter(private val board: ModbusRelayBoard) : MachineStarter {
    // Switching a relay off that is already off changes nothing: a safe "are you there".
    override suspend fun ready(m: LaundryMachineConfig) = board.setRelay(m.relay, false)
    override suspend fun start(m: LaundryMachineConfig, pulses: Int) = board.pulse(m.relay, pulses, m.pulseMs, m.gapMs)
    override suspend fun inputs() = board.readInputs(LaundryMachineConfig.MAX_RELAY)
    override suspend fun ping() = board.setRelay(1, false)
    override suspend fun testPulse(relay: Int) = board.pulse(relay, 1, LaundryMachineConfig.DEFAULT_PULSE_MS, 0)
}

/** Demo mode: no relay module, no card, nothing recorded. */
object DemoStarter : MachineStarter {
    override suspend fun ready(m: LaundryMachineConfig) = true
    override suspend fun start(m: LaundryMachineConfig, pulses: Int): ModbusRelayBoard.PulseResult {
        delay(minOf(2_500L, pulses * (m.pulseMs + m.gapMs)))
        return ModbusRelayBoard.PulseResult(pulses, pulses)
    }
    override suspend fun inputs(): BooleanArray? = null
    override suspend fun ping() = true
    override suspend fun testPulse(relay: Int) = ModbusRelayBoard.PulseResult(1, 1)
}

/**
 * Aegis Laundry's customer flow: pick a machine -> confirm -> card -> the machine gets
 * its coin pulses -> "press Start on the machine". Card only for now (VIP / coupons as in
 * Wash and Timer come later).
 *
 * Money rules, the same as Wash / Timer: the module is checked before the card is
 * charged (never charge for a machine we can't start); the sale is recorded PENDING
 * before the bank call; a machine that doesn't start leaves the sale PAID +
 * HARDWARE_ERROR / PARTIAL_DISPENSE and follows RefundPolicy (automatic reversal is off:
 * a person refunds or compensates).
 *
 * Technician card test (TestSale, as in Wash / Timer): the next sale charges a small amount
 * on a real card, recorded TEST_* (Cael keeps those out of revenue). On demo machines (no
 * LAUNDRY products yet) the machine start is simulated, and the record says so
 * (hardware_status SIMULATED, no product).
 */
class LaundryViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Screen {
        object Home : Screen
        /** The machine and what the customer chose for it (temperature / add-ons / dryer time). */
        data class Confirm(val order: LaundryMachineConfig.Order) : Screen { val machine get() = order.machine }
        data class Paying(val machine: LaundryMachineConfig, val message: String, val chargeCents: Int = machine.priceCents, val test: Boolean = false) : Screen
        data class Starting(val machine: LaundryMachineConfig) : Screen
        /** [summary]: what to pick on the machine ("Hot · Heavy soil"), empty for a dryer / single-pulse machine. */
        data class Started(val machine: LaundryMachineConfig, val summary: String = "") : Screen
        data class Declined(val message: String) : Screen
        /** The machine didn't (fully) start. [paid]: the card was charged. */
        data class StartFailed(val machine: LaundryMachineConfig, val paid: Boolean) : Screen
        data class Unavailable(val machine: LaundryMachineConfig) : Screen
        /** Paying by VIP: the member shows their VIP QR (the only kind of VIP card in use). */
        data class VipPay(val order: LaundryMachineConfig.Order, val message: String = "Show your VIP member QR code") : Screen
        /** Paying from a VIP balance / applying a coupon. */
        data class Working(val machine: LaundryMachineConfig, val message: String) : Screen
        /** Demo-mode VIP page: sample load plans, nothing charged. */
        object VipDemo : Screen
        data class VipDemoPaying(val plan: DemoPlan) : Screen
        data class VipDemoDone(val plan: DemoPlan) : Screen
    }

    /** A scanned VIP member code or coupon, used on the next machine the customer confirms. */
    sealed interface Pending {
        data class Vip(val cardUid: String, val memberCode: String, val balanceCents: Int, val tier: String? = null) : Pending {
            /** Wash's member discount: GOLD 10%, PLATINUM 15% (docs/system_architecture.md v2.49). */
            val discountPct: Int get() = when (tier) { "PLATINUM" -> 15; "GOLD" -> 10; else -> 0 }
        }
        data class Coupon(val code: String, val type: String, val value: Int, val applicableProductId: String?) : Pending
    }

    data class DemoPlan(val payCents: Int, val getCents: Int)

    data class UiState(
        val machines: List<LaundryMachineConfig> = emptyList(),
        /** Products that couldn't be used, for the technician. */
        val problems: List<String> = emptyList(),
        val screen: Screen = Screen.Home,
        /** Relay channels whose machine reports "running" (module inputs). */
        val running: Set<Int> = emptySet(),
        /** Machine number -> when the cycle this terminal paid for should end (the "in use" estimate). */
        val endsAt: Map<Int, Long> = emptyMap(),
        val locked: Boolean = false,
        val demoMode: Boolean = false,
        val configLoaded: Boolean = false,
        /** Technician: the next sale is a small real card test of this amount. */
        val testArmedCents: Int? = null,
        /** Technician: relay module answered the last check (null = not checked). */
        val moduleOk: Boolean? = null,
        val techMessage: String? = null,
        /** Scanned VIP card / coupon waiting for a machine. */
        val pending: Pending? = null,
        /** Short message on the home screen (code not recognized, ...). */
        val notice: String? = null,
    ) {
        /** What [m] costs after a pending coupon (a VIP balance pays the full price). */
        /** Card price: after a scanned coupon. */
        fun priceFor(o: LaundryMachineConfig.Order): Int = when (val p = pending) {
            is Pending.Coupon -> couponPrice(o.totalCents, p)
            else -> o.totalCents
        }

        /** VIP price: the member's tier discount. */
        fun vipPrice(o: LaundryMachineConfig.Order, vip: Pending.Vip): Int = o.totalCents - o.totalCents * vip.discountPct / 100
        fun busy(m: LaundryMachineConfig) =
            (m.input != null && m.input in running) || (demoMode && (endsAt[m.machineNo] ?: 0L) > System.currentTimeMillis())
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var payment: IPaymentProvider? = null
    private var starter: MachineStarter = DemoStarter
    private var deviceSn = ""
    private var cardInfo: IPaymentProvider.CardInfo? = null
    private var homeJob: Job? = null
    private var pollJob: Job? = null

    fun attach(payment: IPaymentProvider?, starter: MachineStarter, deviceSn: String) {
        this.payment = payment
        this.starter = starter
        this.deviceSn = deviceSn
        startInputPolling()
        viewModelScope.launch { TestSale.armed.collect { a -> _state.update { it.copy(testArmedCents = a?.cents) } } }
    }

    // ---- scanned VIP member code / coupon -----------------------------------------

    private var pendingJob: Job? = null
    private var noticeJob: Job? = null

    /**
     * A code from the scanner (home or confirm screen). 6 letters / digits = VIP member
     * code, anything else = coupon -- the routing Wash and Timer use. Nothing is spent
     * here: the balance / coupon is only used once a machine is confirmed. Demo mode
     * makes up a card or coupon and never asks the server (no real member data on show).
     */
    fun onScanned(raw: String) {
        val code = raw.trim()
        val screen = _state.value.screen
        if (code.isEmpty() || (screen != Screen.Home && screen !is Screen.Confirm && screen !is Screen.VipPay)) return
        val vipCode = Regex("^[A-Za-z0-9]{6}$").matches(code)
        val onVipPay = screen is Screen.VipPay
        if (_state.value.demoMode) {
            if (vipCode || onVipPay) { setPending(Pending.Vip("demo", if (vipCode) code.uppercase() else "DEMO42", DEMO_VIP_BALANCE, "GOLD")); vipFound() }
            else setPending(Pending.Coupon(code, "FIXED_OFF", 100, null))
            return
        }
        if (onVipPay && !vipCode) { setVipMessage("That isn't a VIP member code. Scan again, or go back and pay by card."); return }
        viewModelScope.launch {
            if (vipCode) {
                val uid = VipRepository.resolveCardUidByQrCode(code)
                val card = uid?.let { VipRepository.getVipCard(it) }
                val lowest = _state.value.machines.minOfOrNull { it.priceCents }
                when {
                    uid == null || card == null -> { notice("Member code not recognized"); setVipMessage("Member code not recognized. Scan again, or pay by card.") }
                    !card.is_active -> { notice("This VIP card is deactivated. Please contact the attendant."); setVipMessage("This VIP card is deactivated. Please see the attendant.") }
                    lowest != null && card.balance_cents < lowest ->
                        notice("Not enough VIP balance (${money(card.balance_cents)}). Top up with VIP or pay by card.")
                    else -> { setPending(Pending.Vip(uid, code.uppercase(), card.balance_cents, card.tier)); vipFound() }
                }
                return@launch
            }
            when (val peek = CouponRepository.peekCoupon(code, deviceSn)) {
                is CouponPeekResult.Success -> {
                    val forHere = peek.applicableProductId == null || _state.value.machines.any { it.productId == peek.applicableProductId }
                    if (forHere) setPending(Pending.Coupon(code, peek.type, peek.value, peek.applicableProductId))
                    else notice("This coupon can't be used here")
                }
                else -> notice("This code can't be used. Please see the attendant.")
            }
        }
    }

    fun clearPending() {
        pendingJob?.cancel()
        _state.update { it.copy(pending = null) }
    }

    fun showNotice(text: String) = notice(text)

    private fun setPending(p: Pending) {
        pendingJob?.cancel()
        _state.update { it.copy(pending = p, notice = null) }
        TtsManager.speak(if (p is Pending.Vip) "VIP card recognized. Please choose your machine." else "Coupon recognized. Please choose your machine.")
        pendingJob = viewModelScope.launch {
            delay(PENDING_MS)
            // Never drop it from under the customer mid-purchase.
            while (_state.value.screen != Screen.Home) delay(1_000)
            _state.update { if (it.pending == p) it.copy(pending = null) else it }
        }
    }

    private fun notice(text: String) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = text) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _state.update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    // ---- demo VIP page --------------------------------------------------------

    fun openVipDemo() { if (idle) _state.update { it.copy(screen = Screen.VipDemo) } }

    fun buyVipDemo(plan: DemoPlan) {
        _state.update { it.copy(screen = Screen.VipDemoPaying(plan)) }
        viewModelScope.launch {
            delay(DEMO_CARD_MS)
            if (_state.value.screen is Screen.VipDemoPaying) showThenHome(Screen.VipDemoDone(plan), 6000)
        }
    }

    fun closeVip() {
        if (_state.value.screen is Screen.VipDemo || _state.value.screen is Screen.VipDemoDone) _state.update { it.copy(screen = Screen.Home) }
    }

    // ---- technician ------------------------------------------------------------

    fun armTest(cents: Int) = TestSale.arm(cents)
    fun disarmTest() = TestSale.disarm()

    fun checkModule() {
        _state.update { it.copy(techMessage = "Checking the relay module...") }
        viewModelScope.launch {
            val ok = starter.ping()
            _state.update { it.copy(moduleOk = ok, techMessage = if (ok) "Relay module answered" else "Relay module did not answer") }
        }
    }

    fun testPulse(relay: Int) {
        _state.update { it.copy(techMessage = "Pulse on relay $relay...") }
        viewModelScope.launch {
            val r = starter.testPulse(relay)
            _state.update { it.copy(techMessage = if (r.ok) "Relay $relay: 1 pulse sent" else "Relay $relay: ${r.message ?: "no answer"}") }
        }
    }

    fun applyConfig(config: AppConfig) {
        val (machines, problems) = LaundryMachineConfig.fromProducts(config.products)
        problems.forEach { Log.w(TAG, "Machine not sellable: $it") }
        // No laundry machines set up for this store yet: demo mode (sample machines,
        // no card, nothing recorded), never a real sale on made-up machines.
        val demo = machines.isEmpty()
        _state.update {
            it.copy(machines = if (demo) LaundryMachineConfig.DEMO else machines, problems = problems,
                configLoaded = true, demoMode = demo,
                // Demo: a couple of machines mid-cycle, so the "in use" look shows too.
                endsAt = if (demo) DEMO_RUNNING.mapValues { (_, min) -> System.currentTimeMillis() + min * 60_000L } else it.endsAt)
        }
    }

    fun setLocked(locked: Boolean) = _state.update { it.copy(locked = locked) }

    fun setDemoMode(on: Boolean) = _state.update { it.copy(demoMode = on, screen = Screen.Home) }

    val idle: Boolean get() = _state.value.screen == Screen.Home

    fun select(m: LaundryMachineConfig) {
        val s = _state.value
        if (s.screen != Screen.Home || s.locked || s.busy(m)) return
        _state.update { it.copy(screen = Screen.Confirm(LaundryMachineConfig.Order(m))) }
    }

    private fun updateOrder(change: (LaundryMachineConfig.Order) -> LaundryMachineConfig.Order) {
        val c = _state.value.screen as? Screen.Confirm ?: return
        _state.update { it.copy(screen = Screen.Confirm(change(c.order))) }
    }

    fun chooseTemp(t: LaundryMachineConfig.Option) = updateOrder { it.copy(temp = t) }
    fun toggleAddon(a: LaundryMachineConfig.Option) = updateOrder { if (a in it.addons) it.copy(addons = it.addons - a) else it.copy(addons = it.addons + a) }
    fun chooseDry(d: LaundryMachineConfig.DryTime) = updateOrder { it.copy(dry = d) }

    fun back() {
        if (_state.value.screen is Screen.Confirm) _state.update { it.copy(screen = Screen.Home) }
    }

    /** The Card button (a scanned VIP card is left out of this payment; a coupon still applies). */
    fun payCard() = confirm(useVip = false)

    /**
     * The VIP card button: pay with an already-scanned member card, or go to the VIP pay
     * screen. VIP cards are QR passes on the member's phone (no physical cards), so true =
     * open the scanner now.
     */
    fun payVip(): Boolean {
        val c = _state.value.screen as? Screen.Confirm ?: return false
        if (_state.value.pending is Pending.Vip) { confirm(useVip = true); return false }
        _state.update { it.copy(screen = Screen.VipPay(c.order)) }
        TtsManager.speak("Please show your VIP member code to the camera.")
        return true
    }

    /** Leaves the VIP pay screen back to the options. */
    fun vipBack() {
        val v = _state.value.screen as? Screen.VipPay ?: return
        _state.update { it.copy(screen = Screen.Confirm(v.order)) }
    }

    /** The scanner closed without a member code (cancelled / timed out). */
    fun vipScanEnded() {
        val v = _state.value.screen as? Screen.VipPay ?: return
        _state.update { it.copy(screen = v.copy(message = "No member code read. Scan again, or go back and pay by card.")) }
    }

    private fun setVipMessage(m: String) {
        val v = _state.value.screen as? Screen.VipPay ?: return
        _state.update { it.copy(screen = v.copy(message = m)) }
    }

    /** A VIP card was recognised on the VIP pay screen: pay with it. */
    private fun vipFound() {
        val v = _state.value.screen as? Screen.VipPay ?: return
        _state.update { it.copy(screen = Screen.Confirm(v.order)) }
        confirm(useVip = true)
    }

    fun confirm() = confirm(useVip = _state.value.pending is Pending.Vip)

    private fun confirm(useVip: Boolean) {
        val order = (_state.value.screen as? Screen.Confirm)?.order ?: return
        val m = order.machine
        // A demo machine can still take a technician's real card test; its start is simulated.
        val simulate = m.productId.startsWith(DEMO_PREFIX)
        val demo = _state.value.demoMode && TestSale.peek() == null
        _state.update { it.copy(screen = Screen.Paying(m, "Checking the machine…")) }
        viewModelScope.launch {
            if (!starter(demo || simulate).ready(m)) {
                DiagnosticManager.reportError(deviceSn, "LAUNDRY_RELAY_UNREACHABLE", severity = "CRITICAL", trace = "relay ${m.relay} machine ${m.machineNo}")
                showThenHome(Screen.Unavailable(m), 6000)
                return@launch
            }
            val pending = _state.value.pending.let { if (it is Pending.Vip && !useVip) null else it }
            clearPending()
            val price = _state.value.copy(pending = pending).priceFor(order)
            when {
                pending is Pending.Vip -> payWithVip(order, pending, demo, simulate)
                pending is Pending.Coupon && price <= 0 -> redeemFree(order, pending, demo, simulate)
                demo -> {
                    // Shows the card step as a customer would see it, then "approves": no card, nothing charged.
                    _state.update { it.copy(screen = Screen.Paying(m, "Tap, insert or swipe your card", price)) }
                    delay(DEMO_CARD_MS)
                    if (_state.value.screen !is Screen.Paying) return@launch
                    onPaid(order, ref = "DEMO_" + System.currentTimeMillis(), bankRef = "", entryMode = "DEMO", authCode = null, demo = true)
                }
                pending is Pending.Coupon -> cardSale(order, simulate, price) {
                    // Paid by card already: use the coupon up now. A lost race (used elsewhere
                    // meanwhile) still starts the machine -- they paid.
                    if (CouponRepository.redeemCoupon(pending.code, deviceSn) !is CouponRedeemResult.Success) {
                        DiagnosticManager.reportError(deviceSn, "COUPON_REDEEM_AFTER_PAY_FAILED", severity = "WARNING", trace = "code=${pending.code}")
                    }
                }
                else -> cardSale(order, simulate, price)
            }
        }
    }

    fun cancelPayment() {
        if (_state.value.screen !is Screen.Paying) return
        if (_state.value.demoMode) { _state.update { it.copy(screen = Screen.Home) }; return }
        payment?.cancelCurrentTransaction()
    }

    private fun starter(demo: Boolean) = if (demo) DemoStarter else starter

    private fun cardSale(order: LaundryMachineConfig.Order, simulate: Boolean, amountCents: Int, afterApproved: (suspend () -> Unit)? = null) {
        val m = order.machine
        val pay = payment ?: run { showThenHome(Screen.Declined("Card payment is not available"), 5000); return }
        val testCents = TestSale.consume()
        val charge = testCents ?: amountCents
        val ref = (if (testCents != null) TestSale.REF_PREFIX + "LAUNDRY_" else "LAUNDRY_") + System.currentTimeMillis()
        _state.update { it.copy(screen = Screen.Paying(m, "Tap, insert or swipe your card", charge, test = testCents != null)) }
        TtsManager.speak("Please present your card")
        viewModelScope.launch {
            TransactionRepository.recordTransaction(
                getApplication(),
                TransactionRecord(
                    device_sn = deviceSn,
                    amount = charge,
                    payment_status = "PENDING",
                    ecr_ref_num = ref,
                    payment_method = "CREDIT_CARD",
                    product_id = m.productId.takeUnless { simulate },
                    order_note = order.summary.ifBlank { null },
                )
            )
            var cancelled = false
            pay.startSale(charge, ref, object : IPaymentProvider.PaymentCallback {
                override fun onCardInfo(info: IPaymentProvider.CardInfo) { cardInfo = info }
                override fun onCancelled() { cancelled = true }
                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    viewModelScope.launch {
                        afterApproved?.invoke()
                        onPaid(order, ref, refNum, entryMode, authCode, demo = false, simulate = simulate, chargeCents = charge)
                    }
                }
                override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                    viewModelScope.launch {
                        TransactionRepository.recordFailedCardSale(getApplication(), ref, cardInfo.also { cardInfo = null }, cancelled)
                    }
                    if (isHardwareFault) DiagnosticManager.reportError(deviceSn, "CARD_READER_FAULT", severity = "CRITICAL", trace = errorMsg)
                    showThenHome(Screen.Declined(if (cancelled) "Payment cancelled" else "Payment not completed"), 4000)
                }
                override fun onResultUnknown(message: String) {
                    // The card may have been charged: leave it PENDING (PendingResolver settles
                    // it) and start nothing, the same as an approval we never heard about.
                    DiagnosticManager.reportError(deviceSn, "LAUNDRY_SALE_UNKNOWN", severity = "CRITICAL", trace = "$ref $message")
                    showThenHome(Screen.Declined("Payment result unknown. Please see the attendant before paying again."), 10000)
                }
                override fun onProgress(message: String) {
                    val cur = _state.value.screen
                    if (cur is Screen.Paying && message.isNotBlank()) _state.update { it.copy(screen = cur.copy(message = message)) }
                }
            })
        }
    }

    private suspend fun onPaid(
        order: LaundryMachineConfig.Order, ref: String, bankRef: String, entryMode: String, authCode: String?, demo: Boolean,
        simulate: Boolean = false, chargeCents: Int = order.totalCents,
    ) {
        val m = order.machine
        if (!demo) {
            val card = cardInfo.also { cardInfo = null }
            TransactionRepository.updatePaymentStatus(
                getApplication(), ref, "PAID", entryMode,
                paymentMethod = card?.let { CardTypeClassifier.paymentMethod(it.scheme, it.aid, it.brand) },
                cardAid = card?.aid, cardBin = card?.bin, cardBrand = card?.brand, authCode = authCode,
            )
        }
        _state.update { it.copy(screen = Screen.Starting(m)) }
        val result = starter(demo || simulate).start(m, order.pulses)
        if (result.ok) {
            if (!demo) TransactionRepository.updateHardwareStatus(getApplication(), ref, if (simulate) "SIMULATED" else "ACK_RECEIVED")
            markRunning(order)
            TtsManager.speak("Payment approved. ${startPhrase(order)}")
            showThenHome(Screen.Started(m, startSummary(order)), 12000)
            return
        }
        // Didn't (fully) start. Some pulses may have reached the machine: never send more.
        Log.e(TAG, "Machine ${m.machineNo} start failed: $result")
        TtsManager.speak("Sorry, the machine could not start. Please contact the attendant.")
        showThenHome(Screen.StartFailed(m, paid = !demo), 12000)
        if (demo) return
        val status = if (result.delivered > 0 || result.uncertain) "PARTIAL_DISPENSE" else "HARDWARE_ERROR"
        TransactionRepository.updateHardwareStatus(getApplication(), ref, status)
        DiagnosticManager.reportError(
            deviceSn, if (result.stuckOn) "LAUNDRY_RELAY_STUCK" else "LAUNDRY_START_FAIL", severity = "CRITICAL",
            trace = "$ref machine ${m.machineNo} relay ${m.relay}: ${result.delivered}/${result.requested} pulses, " +
                "uncertain=${result.uncertain} stuckOn=${result.stuckOn} ${result.message ?: ""}",
        )
        if (RefundPolicy.AUTO_REVERSAL && result.delivered == 0 && !result.uncertain && bankRef.isNotEmpty()) {
            payment?.voidOrRefund(bankRef, chargeCents) { ok, method ->
                viewModelScope.launch {
                    if (ok) TransactionRepository.updatePaymentStatus(getApplication(), ref, if (method == "REFUND") "REFUNDED" else "VOIDED")
                    else DiagnosticManager.reportError(deviceSn, "VOID_AND_REFUND_FAILED", severity = "CRITICAL", trace = ref)
                }
            }
        } else {
            RefundPolicy.reportManualRefund(deviceSn, ref, chargeCents,
                "laundry machine ${m.machineNo} got ${result.delivered} of ${result.requested} pulses")
        }
    }

    private fun payWithVip(order: LaundryMachineConfig.Order, vip: Pending.Vip, demo: Boolean, simulate: Boolean) {
        val m = order.machine
        _state.update { it.copy(screen = Screen.Working(m, "Paying from your VIP balance…")) }
        viewModelScope.launch {
            if (demo) {
                delay(1_200)
                startPrepaid(order, "DEMO_VIP_" + System.currentTimeMillis(), demo = true, simulate = true, paidWith = "VIP", spoken = "Paid from your VIP balance.")
                return@launch
            }
            val vipCents = _state.value.vipPrice(order, vip)
            when (val r = VipRepository.deductBalance(vip.cardUid, vipCents)) {
                is VipDeductResult.Success -> {
                    val ref = "VIP_${vip.cardUid}_${System.currentTimeMillis()}"
                    TransactionRepository.recordTransaction(
                        getApplication(),
                        TransactionRecord(
                            device_sn = deviceSn, amount = vipCents, payment_status = "PAID", ecr_ref_num = ref,
                            payment_method = "VIP_CARD", product_id = m.productId.takeUnless { simulate },
                            vip_card_uid = vip.cardUid, entry_mode = "QR_SCAN", order_note = order.summary.ifBlank { null },
                        ),
                    )
                    startPrepaid(order, ref, demo = false, simulate = simulate, paidWith = "VIP balance", spoken = "Paid from your VIP balance.")
                }
                is VipDeductResult.Rejected -> {
                    _state.update { it.copy(screen = Screen.Home) }
                    notice(
                        when (r.reason) {
                            "insufficient_balance" -> "Not enough VIP balance (${money(vip.balanceCents)}). Please pay by card or top up."
                            "daily_limit_exceeded" -> "This card's daily limit is reached."
                            else -> "This VIP card can't be used. Please see the attendant."
                        }
                    )
                }
                else -> { _state.update { it.copy(screen = Screen.Home) }; notice("Network problem. Please try again.") }
            }
        }
    }

    private fun redeemFree(order: LaundryMachineConfig.Order, coupon: Pending.Coupon, demo: Boolean, simulate: Boolean) {
        val m = order.machine
        _state.update { it.copy(screen = Screen.Working(m, "Applying your coupon…")) }
        viewModelScope.launch {
            if (demo) {
                delay(1_200)
                startPrepaid(order, "DEMO_COUPON_" + System.currentTimeMillis(), demo = true, simulate = true, paidWith = "coupon", spoken = "Your coupon covers it.")
                return@launch
            }
            when (CouponRepository.redeemCoupon(coupon.code, deviceSn)) {
                is CouponRedeemResult.Success -> {
                    val ref = "COUPON_${System.currentTimeMillis()}"
                    TransactionRepository.recordTransaction(
                        getApplication(),
                        TransactionRecord(
                            device_sn = deviceSn, amount = 0, payment_status = "PAID", ecr_ref_num = ref,
                            payment_method = "COUPON", product_id = m.productId.takeUnless { simulate }, entry_mode = "QR_SCAN",
                            order_note = order.summary.ifBlank { null },
                        ),
                    )
                    startPrepaid(order, ref, demo = false, simulate = simulate, paidWith = "coupon ${coupon.code}", spoken = "Your coupon covers it.")
                }
                else -> { _state.update { it.copy(screen = Screen.Home) }; notice("This coupon can't be used. Please see the attendant.") }
            }
        }
    }

    /**
     * Starts [m] after a VIP balance / free coupon payment (already recorded PAID). Neither
     * can be reversed from the terminal, so a machine that won't start raises a CRITICAL
     * alert for staff to compensate in Cael.
     */
    private suspend fun startPrepaid(order: LaundryMachineConfig.Order, ref: String, demo: Boolean, simulate: Boolean, paidWith: String, spoken: String) {
        val m = order.machine
        _state.update { it.copy(screen = Screen.Starting(m)) }
        val result = starter(demo || simulate).start(m, order.pulses)
        if (result.ok) {
            if (!demo) TransactionRepository.updateHardwareStatus(getApplication(), ref, if (simulate) "SIMULATED" else "ACK_RECEIVED")
            markRunning(order)
            TtsManager.speak("$spoken ${startPhrase(order)}")
            showThenHome(Screen.Started(m, startSummary(order)), 12000)
            return
        }
        TtsManager.speak("Sorry, the machine could not start. Please contact the attendant.")
        showThenHome(Screen.StartFailed(m, paid = true), 12000)
        if (demo) return
        TransactionRepository.updateHardwareStatus(getApplication(), ref, if (result.delivered > 0 || result.uncertain) "PARTIAL_DISPENSE" else "HARDWARE_ERROR")
        DiagnosticManager.reportError(
            deviceSn, "LAUNDRY_PREPAID_NOT_STARTED", severity = "CRITICAL",
            trace = "$ref paid with $paidWith (${order.totalCents}c ${order.summary}), machine ${m.machineNo} got ${result.delivered}/${result.requested} pulses -- compensate in Cael",
        )
    }

    /** What to pick on the machine: the washer's temperature / add-ons (the credit covers them). */
    private fun startSummary(o: LaundryMachineConfig.Order) = if (o.machine.isWasher) o.summary else ""

    private fun startPhrase(o: LaundryMachineConfig.Order): String {
        val pick = startSummary(o)
        return if (pick.isBlank()) "Please press start on machine ${o.machine.machineNo}."
        else "On machine ${o.machine.machineNo}, choose ${pick.replace(" · ", " and ")}, then press start."
    }

    private fun markRunning(o: LaundryMachineConfig.Order) {
        val min = o.minutes ?: return
        _state.update { it.copy(endsAt = it.endsAt + (o.machine.machineNo to System.currentTimeMillis() + min * 60_000L)) }
    }

    private fun showThenHome(screen: Screen, afterMs: Long) {
        homeJob?.cancel()
        _state.update { it.copy(screen = screen) }
        homeJob = viewModelScope.launch {
            delay(afterMs)
            if (_state.value.screen == screen) _state.update { it.copy(screen = Screen.Home) }
        }
    }

    /** Machines wired with a "running" signal: read the module every few seconds while idle. */
    private fun startInputPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val s = _state.value
                if (!s.demoMode && s.screen == Screen.Home && s.machines.any { it.input != null }) {
                    starter.inputs()?.let { bits ->
                        val running = bits.indices.filter { bits[it] }.map { it + 1 }.toSet()
                        _state.update { it.copy(running = running) }
                    }
                }
                delay(INPUT_POLL_MS)
            }
        }
    }

    companion object {
        private const val TAG = "LaundryVM"
        private const val INPUT_POLL_MS = 5_000L
        private const val DEMO_CARD_MS = 3_000L
        private const val PENDING_MS = 90_000L
        private const val NOTICE_MS = 6_000L
        const val DEMO_VIP_BALANCE = 4_500
        val DEMO_PLANS = listOf(DemoPlan(2_000, 2_200), DemoPlan(5_000, 6_000), DemoPlan(10_000, 12_500))
        const val DEMO_PREFIX = "demo-"
        /** Demo machines shown running. */
        val DEMO_RUNNING = mapOf(2 to 23, 7 to 18)
    }
}


private fun money(cents: Int) = "$" + String.format(java.util.Locale.US, "%.2f", cents / 100.0)

/** Same formula as Wash / Timer (docs/coupon_redemption_integration.md §3.2): never below 0. */
internal fun couponPrice(priceCents: Int, c: LaundryViewModel.Pending.Coupon): Int = when (c.type) {
    "PERCENT_OFF" -> priceCents - (priceCents * c.value / 100)
    "FIXED_OFF" -> maxOf(0, priceCents - c.value)
    "FREE_WASH" -> 0
    else -> priceCents
}
