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
    suspend fun start(m: LaundryMachineConfig): ModbusRelayBoard.PulseResult
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
    override suspend fun start(m: LaundryMachineConfig) = board.pulse(m.relay, m.pulses, m.pulseMs, m.gapMs)
    override suspend fun inputs() = board.readInputs(LaundryMachineConfig.MAX_RELAY)
    override suspend fun ping() = board.setRelay(1, false)
    override suspend fun testPulse(relay: Int) = board.pulse(relay, 1, LaundryMachineConfig.DEFAULT_PULSE_MS, 0)
}

/** Demo mode: no relay module, no card, nothing recorded. */
object DemoStarter : MachineStarter {
    override suspend fun ready(m: LaundryMachineConfig) = true
    override suspend fun start(m: LaundryMachineConfig): ModbusRelayBoard.PulseResult {
        delay(m.pulses * (m.pulseMs + m.gapMs))
        return ModbusRelayBoard.PulseResult(m.pulses, m.pulses)
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
        data class Confirm(val machine: LaundryMachineConfig) : Screen
        data class Paying(val machine: LaundryMachineConfig, val message: String, val chargeCents: Int = machine.priceCents, val test: Boolean = false) : Screen
        data class Starting(val machine: LaundryMachineConfig) : Screen
        data class Started(val machine: LaundryMachineConfig) : Screen
        data class Declined(val message: String) : Screen
        /** The machine didn't (fully) start. [paid]: the card was charged. */
        data class StartFailed(val machine: LaundryMachineConfig, val paid: Boolean) : Screen
        data class Unavailable(val machine: LaundryMachineConfig) : Screen
    }

    data class UiState(
        val machines: List<LaundryMachineConfig> = emptyList(),
        /** Products that couldn't be used, for the technician. */
        val problems: List<String> = emptyList(),
        val screen: Screen = Screen.Home,
        /** Relay channels whose machine reports "running" (module inputs). */
        val running: Set<Int> = emptySet(),
        /** Machine number -> when this terminal last started it (for the "in use" estimate). */
        val startedAt: Map<Int, Long> = emptyMap(),
        val locked: Boolean = false,
        val demoMode: Boolean = false,
        val configLoaded: Boolean = false,
        /** Technician: the next sale is a small real card test of this amount. */
        val testArmedCents: Int? = null,
        /** Technician: relay module answered the last check (null = not checked). */
        val moduleOk: Boolean? = null,
        val techMessage: String? = null,
    ) {
        fun busy(m: LaundryMachineConfig) =
            (m.input != null && m.input in running) || (demoMode && m.machineNo in startedAt)
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
                startedAt = if (demo) DEMO_RUNNING.associateWith { n -> System.currentTimeMillis() - n * 5 * 60_000L } else it.startedAt)
        }
    }

    fun setLocked(locked: Boolean) = _state.update { it.copy(locked = locked) }

    fun setDemoMode(on: Boolean) = _state.update { it.copy(demoMode = on, screen = Screen.Home) }

    val idle: Boolean get() = _state.value.screen == Screen.Home

    fun select(m: LaundryMachineConfig) {
        val s = _state.value
        if (s.screen != Screen.Home || s.locked || s.busy(m)) return
        _state.update { it.copy(screen = Screen.Confirm(m)) }
    }

    fun back() {
        if (_state.value.screen is Screen.Confirm) _state.update { it.copy(screen = Screen.Home) }
    }

    fun confirm() {
        val m = (_state.value.screen as? Screen.Confirm)?.machine ?: return
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
            if (demo) {
                // Shows the card step as a customer would see it, then "approves": no card, nothing charged.
                _state.update { it.copy(screen = Screen.Paying(m, "Tap, insert or swipe your card")) }
                delay(DEMO_CARD_MS)
                if (_state.value.screen !is Screen.Paying) return@launch
                onPaid(m, ref = "DEMO_" + System.currentTimeMillis(), bankRef = "", entryMode = "DEMO", authCode = null, demo = true)
            } else cardSale(m, simulate)
        }
    }

    fun cancelPayment() {
        if (_state.value.screen !is Screen.Paying) return
        if (_state.value.demoMode) { _state.update { it.copy(screen = Screen.Home) }; return }
        payment?.cancelCurrentTransaction()
    }

    private fun starter(demo: Boolean) = if (demo) DemoStarter else starter

    private fun cardSale(m: LaundryMachineConfig, simulate: Boolean) {
        val pay = payment ?: run { showThenHome(Screen.Declined("Card payment is not available"), 5000); return }
        val testCents = TestSale.consume()
        val charge = testCents ?: m.priceCents
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
                )
            )
            var cancelled = false
            pay.startSale(charge, ref, object : IPaymentProvider.PaymentCallback {
                override fun onCardInfo(info: IPaymentProvider.CardInfo) { cardInfo = info }
                override fun onCancelled() { cancelled = true }
                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    viewModelScope.launch { onPaid(m, ref, refNum, entryMode, authCode, demo = false, simulate = simulate, chargeCents = charge) }
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
        m: LaundryMachineConfig, ref: String, bankRef: String, entryMode: String, authCode: String?, demo: Boolean,
        simulate: Boolean = false, chargeCents: Int = m.priceCents,
    ) {
        if (!demo) {
            val card = cardInfo.also { cardInfo = null }
            TransactionRepository.updatePaymentStatus(
                getApplication(), ref, "PAID", entryMode,
                paymentMethod = card?.let { CardTypeClassifier.paymentMethod(it.scheme, it.aid, it.brand) },
                cardAid = card?.aid, cardBin = card?.bin, cardBrand = card?.brand, authCode = authCode,
            )
        }
        _state.update { it.copy(screen = Screen.Starting(m)) }
        val result = starter(demo || simulate).start(m)
        if (result.ok) {
            if (!demo) TransactionRepository.updateHardwareStatus(getApplication(), ref, if (simulate) "SIMULATED" else "ACK_RECEIVED")
            _state.update { it.copy(startedAt = it.startedAt + (m.machineNo to System.currentTimeMillis())) }
            TtsManager.speak("Payment approved. Please press start on machine ${m.machineNo}.")
            showThenHome(Screen.Started(m), 12000)
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
        const val DEMO_PREFIX = "demo-"
        /** Demo machines shown running. */
        val DEMO_RUNNING = setOf(2, 7)
    }
}
