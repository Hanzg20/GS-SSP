package com.goldsky.ssp.vending

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.goldsky.ssp.DeviceAdapter
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.vending.db.VendingOrder
import com.goldsky.ssp.vending.logic.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The vend loop (see [VendingState] for the two flows: card first / item first).
 *
 * Money ([VendPayment]): on the Q3mini UPT (real MDB) the terminal's payment app --
 * card first = a hold settled at the item's price after the machine reports the
 * vend, item first = a sale. Anything that ends without an item releases the hold
 * or reverses the sale (never charged-but-unfulfilled). Off the Q3mini there is no
 * vending machine to talk to: a debuggable build simulates (emulator, driven by adb
 * from MainActivity) and records nothing; any other build shows Unavailable.
 */
class VendingViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<VendingState>(VendingState.Booting)
    val uiState: StateFlow<VendingState> = _uiState.asStateFlow()

    private val _french = MutableStateFlow(false)
    val french: StateFlow<Boolean> = _french.asStateFlow()

    private val onQ3mini = DeviceAdapter.getModel() == DeviceAdapter.HardwareModel.WIZARPOS_Q3MINI

    /** VMC-simulator build (CoreConfig.vmcSimulator): simulated machine, real payments. */
    val vmcSimulator = com.goldsky.ssp.common.CoreConfig.vmcSimulator

    // Real hardware only on Q3mini UPT (per docs/wizarpos_upt_integration_spec.md's
    // own "机型检测" guidance); everything else -- and the VMC-simulator build -- uses the simulator.
    private val mdbController: IMdbController =
        if (onQ3mini && !vmcSimulator) WizarPosMdbController(application) else MockMdbController()

    val simulated = !onQ3mini &&
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val payment: VendPayment? = when {
        onQ3mini -> runCatching { VendPayment.Real(PaymentProviderFactory.getPaymentProvider(application, "WIZARPOS")) }
            .onFailure { Log.e(TAG, "No WIZARPOS payment provider: ${it.message}") }.getOrNull()
        simulated -> VendPayment.Simulated()
        else -> null
    }

    /** The open card-first hold, if any: its ref is what gets completed or released. */
    private var holdRef: String? = null

    private var locked = false
    private var timerJob: Job? = null
    private var resultJob: Job? = null

    companion object {
        private const val TAG = "VendingVM"
        private const val DISPENSE_TIMEOUT_MS = 30_000L
        /** How long a card-first customer has to choose on the machine (owner, 2026-11-02). */
        const val SESSION_MS = 60_000L
        /** The card-first hold; an item above it is refused (owner, 2026-11-02). */
        const val SESSION_LIMIT_CENTS = 1000
        private const val RESULT_MS = 6_000L
    }

    init {
        // Initialize local storage
        InventoryManager.init(application)
        OrderRepository.init(application)

        viewModelScope.launch {
            // Opening the MDB board took ~5 s on 255 -- off the main thread.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { setupMdbListeners() }
            if (payment == null || !mdbController.ready) {
                Log.e(TAG, "Unavailable: payment ${if (payment == null) "missing" else "ok"}, MDB ${if (mdbController.ready) "open" else "not open"}")
                _uiState.value = VendingState.Unavailable
            } else {
                if (!simulated) {
                    VendRecovery.run(application, payment) { triggerSync() }
                    triggerSync()
                }
                _uiState.value = if (locked) VendingState.Unavailable else VendingState.Idle
                watchMachine()
            }
        }
    }

    /** The MDB line for the technician panel (reads the board: call off the main thread). */
    fun mdbStatus(): String = mdbController.status()

    /** Is the screen idle (no payment or vend under way)? Ads and the technician panel only then. */
    val idle: Boolean get() = _uiState.value.let { it == VendingState.Idle || it == VendingState.Unavailable }

    /** Remote LOCK / admin deactivation from Cael: no new vends while locked. */
    fun setLocked(locked: Boolean) {
        this.locked = locked
        val s = _uiState.value
        if (locked && s == VendingState.Idle) _uiState.value = VendingState.Unavailable
        else if (!locked && s == VendingState.Unavailable && payment != null && mdbController.ready) _uiState.value = VendingState.Idle
    }

    /**
     * No vending machine on the bus = nothing to sell: START would only put a hold on a
     * card. Idle <-> Unavailable follows the bus every 10 s; a sale in progress is left alone.
     */
    private fun watchMachine() {
        viewModelScope.launch {
            var lastOnline: Boolean? = null
            while (true) {
                val online = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mdbController.machineOnline() } != false
                if (online != lastOnline) Log.i(TAG, "Vending machine ${if (online) "online" else "OFFLINE"}")
                lastOnline = online
                val s = _uiState.value
                if (!online && s == VendingState.Idle) _uiState.value = VendingState.Unavailable
                else if (online && !locked && s == VendingState.Unavailable) _uiState.value = VendingState.Idle
                delay(10_000)
            }
        }
    }

    private fun triggerSync() {
        val syncRequest = OneTimeWorkRequestBuilder<VendingSyncWorker>().build()
        WorkManager.getInstance(getApplication()).enqueue(syncRequest)
    }

    fun toggleLanguage() {
        _french.value = !_french.value
    }

    private fun newRef() = "VND-${System.currentTimeMillis()}"

    /** Written before the card is asked for, so a crash can't lose a payment (VendRecovery). */
    private fun journal(ref: String, kind: String, cents: Int, slot: String) {
        if (simulated) return
        OrderRepository.saveOrder(getApplication(), VendingOrder(
            orderId = ref, amountCents = cents, slot = slot,
            paymentStatus = "PENDING", dispenseStatus = "PENDING", kind = kind,
        ))
    }

    private fun journalApproved(ref: String, status: String, r: VendPayment.Result.Approved) {
        if (simulated) return
        OrderRepository.update(getApplication(), ref) {
            it.copy(
                paymentStatus = status, authCode = r.authCode, entryMode = r.entryMode,
                cardBrand = r.card?.brand, cardAid = r.card?.aid, cardBin = r.card?.bin,
            )
        }
    }

    /** Nothing was charged (declined, or a hold released with no item): no row at all. */
    private fun unjournal(ref: String) {
        if (!simulated) OrderRepository.remove(getApplication(), ref)
    }

    private fun setupMdbListeners() {
        mdbController.initialize()

        mdbController.onVendRequest { amount, slot ->
            Log.i(TAG, "Vend request: item $slot, $amount cents, state ${_uiState.value}")
            when (val s = _uiState.value) {
                is VendingState.SessionOpen -> when {
                    InventoryManager.isSoldOut(slot) -> endSession(VendingState.Reason.SOLD_OUT)
                    amount > s.limitCents -> endSession(VendingState.Reason.OVER_LIMIT)
                    // The hold covers it: dispense now, settle at the price once it dropped.
                    else -> {
                        val ref = holdRef ?: newRef()
                        if (!simulated) OrderRepository.update(getApplication(), ref) { it.copy(amountCents = amount, slot = slot) }
                        dispense(ref, amount, slot)
                    }
                }
                VendingState.Idle, is VendingState.NotCharged, is VendingState.Success -> {
                    resultJob?.cancel()
                    if (InventoryManager.isSoldOut(slot)) {
                        mdbController.denyVend()
                        showResult(VendingState.NotCharged(VendingState.Reason.SOLD_OUT))
                    } else {
                        val request = VendingState.VendRequestReceived(amount, slot)
                        _uiState.value = request
                        // Walked away from the price: refuse the vend, back to idle.
                        timerJob?.cancel()
                        timerJob = viewModelScope.launch {
                            delay(SESSION_MS)
                            if (_uiState.value === request) cancel()
                        }
                    }
                }
                // Unavailable, or busy with a payment: the machine gets a no.
                else -> mdbController.denyVend()
            }
        }

        mdbController.onVendSuccess {
            val s = _uiState.value
            if (s is VendingState.Dispensing) {
                timerJob?.cancel()
                InventoryManager.decrement(getApplication(), s.itemSlot)
                val hold = holdRef
                holdRef = null
                if (hold != null) {
                    mdbController.endSession()
                    // Card first: the customer has the item; now take the price from the hold.
                    payment?.complete(hold, s.amountCents) { ok ->
                        if (!ok) Log.e(TAG, "Completion of $hold failed: item given, money not settled -- check PAYWizard")
                        record(s.transactionId, if (ok) "PAID" else "PENDING", "SUCCESS")
                    }
                } else {
                    record(s.transactionId, "PAID", "SUCCESS")
                }
                showResult(VendingState.Success(s.transactionId, s.amountCents, s.itemSlot))
            }
        }

        mdbController.onVendFailure { code ->
            val s = _uiState.value
            if (s is VendingState.Dispensing) {
                timerJob?.cancel()
                giveBack(s.transactionId, s.amountCents, "MDB_FAILURE_$code")
            }
        }

        // Cancelled on the machine; the board has already answered it.
        mdbController.onMachineCancel {
            Log.i(TAG, "Machine cancelled, state ${_uiState.value}")
            when (_uiState.value) {
                is VendingState.SessionOpen -> endSession(VendingState.Reason.CANCELLED, machineClosed = true)
                is VendingState.VendRequestReceived -> {
                    timerJob?.cancel()
                    showResult(VendingState.NotCharged(VendingState.Reason.CANCELLED))
                }
                // A payment under way: the screen has moved on, so an approval is reversed / released.
                is VendingState.Authorizing -> showResult(VendingState.NotCharged(VendingState.Reason.CANCELLED))
                // Dispensing: the machine still owes success / failure, or the timeout gives it back.
                else -> {}
            }
        }
    }

    /** Card first: START touched on the idle screen. */
    fun startSession() {
        val pay = payment ?: return
        if (locked || _uiState.value != VendingState.Idle) return
        val ref = newRef()
        journal(ref, "HOLD", SESSION_LIMIT_CENTS, "")
        _uiState.value = VendingState.Authorizing(null)
        pay.hold(SESSION_LIMIT_CENTS, ref) { r ->
            if (_uiState.value != VendingState.Authorizing(null)) {
                // The screen moved on meanwhile; never leave a hold behind.
                if (r is VendPayment.Result.Approved) pay.release(ref) { ok -> if (ok) unjournal(ref) }
                else unjournal(ref)
                return@hold
            }
            when (r) {
                is VendPayment.Result.Approved -> {
                    journalApproved(ref, "AUTHORIZED", r)
                    holdRef = ref
                    mdbController.beginSession(SESSION_LIMIT_CENTS)
                    val deadline = System.currentTimeMillis() + SESSION_MS
                    _uiState.value = VendingState.SessionOpen(SESSION_LIMIT_CENTS, deadline)
                    timerJob?.cancel()
                    timerJob = viewModelScope.launch {
                        delay(SESSION_MS)
                        if (_uiState.value is VendingState.SessionOpen) endSession(VendingState.Reason.NO_SELECTION)
                    }
                }
                VendPayment.Result.Declined -> {
                    unjournal(ref)
                    showResult(VendingState.NotCharged(VendingState.Reason.DECLINED))
                }
                is VendPayment.Result.Failed -> {
                    // No clear answer: a hold may exist -- release it to be sure. It stays in the
                    // journal until the release is confirmed (VendRecovery retries it).
                    pay.release(ref) { ok -> if (ok) unjournal(ref) }
                    showResult(VendingState.NotCharged(VendingState.Reason.DECLINED))
                }
            }
        }
    }

    /**
     * Ends a card-first session without an item: the machine's session is closed (and a
     * pending selection refused), the hold released.
     */
    private fun endSession(reason: VendingState.Reason, machineClosed: Boolean = false) {
        timerJob?.cancel()
        if (reason == VendingState.Reason.SOLD_OUT || reason == VendingState.Reason.OVER_LIMIT) mdbController.denyVend()
        if (!machineClosed) mdbController.endSession()
        holdRef?.let { ref ->
            payment?.release(ref) { ok ->
                if (ok) unjournal(ref)
                else Log.e(TAG, "Release of hold $ref failed -- VendRecovery retries it on the next start")
            }
        }
        holdRef = null
        showResult(VendingState.NotCharged(reason))
    }

    /** Item first: PAY touched on the price screen. */
    fun startPayment() {
        val pay = payment ?: return
        val s = _uiState.value
        if (s !is VendingState.VendRequestReceived) return
        val ref = newRef()
        journal(ref, "SALE", s.amountCents, s.itemSlot)
        _uiState.value = VendingState.Authorizing(s.amountCents)
        pay.sale(s.amountCents, ref) { r ->
            when (r) {
                is VendPayment.Result.Approved -> {
                    journalApproved(ref, "PAID", r)
                    if (_uiState.value is VendingState.Authorizing) dispense(ref, s.amountCents, s.itemSlot)
                    // The screen moved on meanwhile: give it back.
                    else pay.reverse(ref, s.amountCents) { ok -> record(ref, if (ok) "VOIDED" else "PAID", "FAILED") }
                }
                VendPayment.Result.Declined -> { unjournal(ref); declineVend() }
                // Unknown (PAYWizard didn't answer): left PENDING in the journal, and
                // VendRecovery reverses it if it was charged after all.
                is VendPayment.Result.Failed -> declineVend()
            }
        }
    }

    private fun declineVend() {
        mdbController.denyVend()
        showResult(VendingState.NotCharged(VendingState.Reason.DECLINED))
    }

    /** CANCEL on the session or price screen. */
    fun cancel() {
        when (_uiState.value) {
            is VendingState.SessionOpen -> endSession(VendingState.Reason.CANCELLED)
            is VendingState.VendRequestReceived -> {
                mdbController.denyVend()
                showResult(VendingState.NotCharged(VendingState.Reason.CANCELLED))
            }
            else -> {}
        }
    }

    private fun dispense(txnId: String, amount: Int, slot: String) {
        mdbController.approveVend()
        _uiState.value = VendingState.Dispensing(
            transactionId = txnId,
            amountCents = amount,
            itemSlot = slot,
            deadline = System.currentTimeMillis() + DISPENSE_TIMEOUT_MS
        )
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            delay(DISPENSE_TIMEOUT_MS)
            val s = _uiState.value
            if (s is VendingState.Dispensing && s.transactionId == txnId) giveBack(txnId, amount, "TIMEOUT")
        }
    }

    /** No item came out: release the hold / reverse the sale. */
    private fun giveBack(txnId: String, amount: Int, reason: String) {
        Log.w(TAG, "Vend $txnId failed ($reason): giving the money back")
        val hold = holdRef
        holdRef = null
        val done = { ok: Boolean ->
            if (!ok) Log.e(TAG, "Give-back of $txnId failed -- refund it by hand")
            record(txnId, if (ok) "VOIDED" else "PAID", "FAILED")
        }
        if (hold != null) {
            mdbController.endSession()
            payment?.release(hold, done)
        } else {
            payment?.reverse(txnId, amount, done)
        }
        showResult(VendingState.NotCharged(VendingState.Reason.VEND_FAILED))
    }

    /** Final state of a real vend; the sync worker sends it to the cloud. Simulated vends record nothing. */
    private fun record(txnId: String, paymentStatus: String, dispenseStatus: String) {
        if (simulated) return
        OrderRepository.updateOrder(getApplication(), txnId, dispenseStatus, paymentStatus)
        triggerSync()
    }

    private fun showResult(state: VendingState) {
        _uiState.value = state
        resultJob?.cancel()
        resultJob = viewModelScope.launch {
            delay(RESULT_MS)
            if (_uiState.value == state) {
                _uiState.value = VendingState.Idle
                _french.value = false
            }
        }
    }

    /**
     * Simulator commands from adb (debuggable build, off the Q3mini only -- see
     * MainActivity), and the VMC-simulator build on a real terminal:
     * "select:<cents>:<item>" = the machine asks for a price, "fail" = the next vend jams,
     * "silent" = the machine never answers the next vend, "mcancel" = cancel pressed on the
     * machine, "decline" = the next card is
     * declined (emulator only: a real card answers for itself), "start" / "pay" = the buttons.
     */
    fun simulate(command: String) {
        if (!simulated && !vmcSimulator) return
        Log.i(TAG, "Simulator command: $command")
        val parts = command.split(":")
        when (parts[0]) {
            "select" -> (mdbController as MockMdbController).simulateMachineSelection(
                parts.getOrNull(1)?.toIntOrNull() ?: 175, parts.getOrNull(2) ?: "14")
            "fail" -> mdbController.setFailNext(true)
            "silent" -> (mdbController as? MockMdbController)?.silentNext = true
            "mcancel" -> (mdbController as? MockMdbController)?.machineCancel()
            "decline" -> (payment as? VendPayment.Simulated)?.declineNext = true
            "start" -> startSession()
            "pay" -> startPayment()
        }
    }
}
