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

    /** Technician panel: card first, item first or both (see [PurchaseFlow]). */
    private val _purchaseFlow = MutableStateFlow(SaleMode.flow(application))
    val purchaseFlow: StateFlow<PurchaseFlow> = _purchaseFlow.asStateFlow()

    fun setPurchaseFlow(flow: PurchaseFlow) {
        SaleMode.setFlow(getApplication(), flow)
        _purchaseFlow.value = flow
        Log.i(TAG, "Purchase flow: $flow")
    }

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
    /** Showcase sale mode (technician panel): the machine's MDB sales are refused meanwhile. */
    private var showcase = false
    /**
     * Another of this app's screens is in front (the VIP page, the technician's checks): no
     * MDB sales meanwhile -- the price screen would be hidden, and the card reader busy.
     */
    private var away = false
    private val blocked get() = locked || showcase || away

    /**
     * The VIP banner on the idle screen: the store's best load bonus in % (0 = plans without
     * a bonus); null = no plans, or couldn't fetch them -- no banner (Wash's VipActivity).
     */
    private val _vipBonus = MutableStateFlow<Int?>(null)
    val vipBonus: StateFlow<Int?> = _vipBonus.asStateFlow()

    fun refreshVipPlans() {
        viewModelScope.launch {
            val plans = com.goldsky.ssp.payment.VipLoadRepository.getPlans() ?: return@launch // offline: keep what we had
            _vipBonus.value = plans.filter { it.amount_cents > 0 }
                .takeIf { it.isNotEmpty() }
                ?.maxOf { it.bonus_cents * 100 / it.amount_cents }
        }
    }
    private var timerJob: Job? = null
    private var resultJob: Job? = null

    /**
     * Cael's payment methods for this store (KioskSettings.payment_method_mode): card + scan
     * (VIP member code), card only, or scan only -- the same setting Wash and Timer follow.
     */
    private val _payMode = MutableStateFlow(com.goldsky.ssp.model.PaymentMethodMode.CARD_ONLY)
    val payMode: StateFlow<Int> = _payMode.asStateFlow()
    private val cardAllowed get() = _payMode.value != com.goldsky.ssp.model.PaymentMethodMode.SCAN_ONLY
    private val vipAllowed get() = _payMode.value != com.goldsky.ssp.model.PaymentMethodMode.CARD_ONLY

    fun refreshPayMode() {
        _payMode.value = com.goldsky.ssp.payment.ConfigManager.getConfig()?.settings?.payment_method_mode
            ?: com.goldsky.ssp.model.PaymentMethodMode.CARD_ONLY
        Log.i(TAG, "Payment methods: ${_payMode.value} (0 card + scan, 1 card, 2 scan)")
    }

    // A VIP payment under way (Wash's member-code payment, with an order reference so the
    // balance can be given back -- vip_deduct / vip_refund): the card, whether it runs a
    // card-first session, and its balance after the item was paid (for the success screen).
    private var vipUid: String? = null
    private var vipSession = false
    private var vipBalanceAfter: Int? = null
    private var scanJob: Job? = null

    companion object {
        private const val TAG = "VendingVM"
        private const val DISPENSE_TIMEOUT_MS = 30_000L
        /** How long a card-first customer has to choose on the machine (owner, 2026-11-02). */
        const val SESSION_MS = 60_000L
        /**
         * The card-first hold; an item above it is refused (owner, 2026-11-02: $10). The
         * VMC-simulator build holds $0.10 -- every test amount on a live card is $0.10 (owner,
         * 2026-11-02).
         */
        val SESSION_LIMIT_CENTS: Int get() = if (com.goldsky.ssp.common.CoreConfig.vmcSimulator) 10 else 1000
        private const val RESULT_MS = 6_000L
        /** A VIP card-first session offers at most this much (the machine's own limits apply too). */
        private const val VIP_SESSION_MAX_CENTS = 10_000
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
                _uiState.value = if (blocked) VendingState.Unavailable else VendingState.Idle
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
        applyBlocked()
    }

    /** Showcase sale mode on / off (see ShowcaseViewModel): no MDB sales while it's on. */
    fun setShowcase(on: Boolean) {
        showcase = on
        applyBlocked()
    }

    /** See [away]. */
    fun setAway(on: Boolean) {
        away = on
        applyBlocked()
    }

    private fun applyBlocked() {
        val s = _uiState.value
        if (blocked && s == VendingState.Idle) _uiState.value = VendingState.Unavailable
        else if (!blocked && s == VendingState.Unavailable && payment != null && mdbController.ready) _uiState.value = VendingState.Idle
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
                else if (online && !blocked && s == VendingState.Unavailable) _uiState.value = VendingState.Idle
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
                    amount > s.limitCents -> endSession(
                        if (s.vipBalanceCents != null) VendingState.Reason.VIP_LOW_BALANCE else VendingState.Reason.OVER_LIMIT
                    )
                    // A VIP session: the item's price comes off the balance now.
                    vipSession -> payVipSessionItem(amount, slot)
                    // The hold covers it: dispense now, settle at the price once it dropped.
                    else -> {
                        val ref = holdRef ?: newRef()
                        if (!simulated) OrderRepository.update(getApplication(), ref) { it.copy(amountCents = amount, slot = slot) }
                        dispense(ref, amount, slot)
                    }
                }
                VendingState.Idle, is VendingState.NotCharged, is VendingState.Success -> {
                    resultJob?.cancel()
                    if (_purchaseFlow.value == PurchaseFlow.CARD_FIRST) {
                        // Card first only: no paying for a price here.
                        mdbController.denyVend()
                        showResult(VendingState.NotCharged(VendingState.Reason.START_FIRST))
                    } else if (InventoryManager.isSoldOut(slot)) {
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
                val balance = vipBalanceAfter
                // The session ends by itself: the machine sends SESSION COMPLETE and the board answers
                // END SESSION (WizarPOS 2026-11-05) -- VEND_CANCEL is the machine's word, not ours.
                if (hold != null) {
                    // Card first: the customer has the item; now take the price from the hold.
                    payment?.complete(hold, s.amountCents) { ok ->
                        if (!ok) Log.e(TAG, "Completion of $hold failed: item given, money not settled -- check PAYWizard")
                        record(s.transactionId, if (ok) "PAID" else "PENDING", "SUCCESS")
                    }
                } else {
                    record(s.transactionId, "PAID", "SUCCESS")
                }
                showResult(VendingState.Success(s.transactionId, s.amountCents, s.itemSlot, balance))
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
                is VendingState.Scanning -> stopVipScan(VendingState.Reason.CANCELLED, deny = false)
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
        if (blocked || !cardAllowed || _uiState.value != VendingState.Idle || _purchaseFlow.value == PurchaseFlow.ITEM_FIRST) return
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
        // A selection is waiting for its answer: no.
        if (reason in listOf(VendingState.Reason.SOLD_OUT, VendingState.Reason.OVER_LIMIT, VendingState.Reason.VIP_LOW_BALANCE,
                VendingState.Reason.VIP_INVALID, VendingState.Reason.VIP_OFFLINE)) mdbController.denyVend()
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
        if (s !is VendingState.VendRequestReceived || !cardAllowed) return
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
            is VendingState.Scanning -> stopVipScan(VendingState.Reason.CANCELLED)
            else -> {}
        }
    }

    /**
     * SCAN VIP touched: on the price screen (item first -- the price comes off the balance) or
     * on the idle screen (card first -- a session on the balance). Wash's member-code payment:
     * scan, look the card up, deduct; here with an order reference so a vend that fails gives
     * the balance back (vip_refund), and an unfinished one is given back by VendRecovery.
     */
    fun startVipScan() {
        if (blocked || !vipAllowed) return
        val s = _uiState.value
        val request = s as? VendingState.VendRequestReceived
        if (request == null && (s != VendingState.Idle || _purchaseFlow.value == PurchaseFlow.ITEM_FIRST)) return
        timerJob?.cancel()
        val scanning = VendingState.Scanning(request?.amountCents)
        _uiState.value = scanning
        scanJob = viewModelScope.launch {
            val code = VipScan.code(getApplication())
            if (_uiState.value !== scanning) return@launch
            if (code == null) { stopVipScan(VendingState.Reason.NO_CODE); return@launch }
            val uid = com.goldsky.ssp.payment.VipRepository.resolveCardUidByQrCode(code.trim())
            if (_uiState.value !== scanning) return@launch
            if (uid == null) { stopVipScan(VendingState.Reason.VIP_INVALID); return@launch }
            if (request != null) payVipItem(uid, request, scanning) else openVipSession(uid, scanning)
        }
    }

    /** Ends a scan without a payment; [deny] the machine's waiting selection (item first). */
    private fun stopVipScan(reason: VendingState.Reason, deny: Boolean = true) {
        scanJob?.cancel()
        scanJob = null
        VipScan.stop(getApplication())
        if (deny && (_uiState.value as? VendingState.Scanning)?.amountCents != null) mdbController.denyVend()
        showResult(VendingState.NotCharged(reason))
    }

    private fun reasonFor(r: com.goldsky.ssp.payment.VipDeductResult) = when (r) {
        is com.goldsky.ssp.payment.VipDeductResult.Rejected ->
            if (r.reason == "insufficient_balance") VendingState.Reason.VIP_LOW_BALANCE else VendingState.Reason.VIP_INVALID
        else -> VendingState.Reason.VIP_OFFLINE
    }

    /** Journaled before the deduct, so a crash can't lose it (VendRecovery gives it back). */
    private fun journalVip(ref: String, cents: Int, slot: String, uid: String) {
        if (simulated) return
        OrderRepository.saveOrder(getApplication(), VendingOrder(
            orderId = ref, amountCents = cents, slot = slot, paymentStatus = "PENDING", dispenseStatus = "PENDING",
            kind = "VIP", vipCardUid = uid, entryMode = "QR",
        ))
    }

    /**
     * Takes [cents] off the card for order [ref]: the balance after, or a reason (nothing
     * taken). An unclear answer is given back at once (the refund only returns what [ref]
     * really took); if even that can't be confirmed the order stays for VendRecovery.
     */
    private suspend fun deductVip(uid: String, cents: Int, ref: String, slot: String): Pair<Int?, VendingState.Reason?> {
        journalVip(ref, cents, slot, uid)
        return when (val r = com.goldsky.ssp.payment.VipRepository.deduct(uid, cents, ref)) {
            is com.goldsky.ssp.payment.VipDeductResult.Success -> {
                if (!simulated) OrderRepository.update(getApplication(), ref) { it.copy(paymentStatus = "PAID") }
                r.newBalanceCents to null
            }
            is com.goldsky.ssp.payment.VipDeductResult.Rejected -> { unjournal(ref); null to reasonFor(r) }
            com.goldsky.ssp.payment.VipDeductResult.NetworkError -> {
                if (com.goldsky.ssp.payment.VipRepository.refund(uid, ref)) unjournal(ref)
                null to VendingState.Reason.VIP_OFFLINE
            }
        }
    }

    /** Item first: the price comes off the balance, then the machine vends. */
    private suspend fun payVipItem(uid: String, request: VendingState.VendRequestReceived, scanning: VendingState.Scanning) {
        val ref = newRef()
        val (balance, reason) = deductVip(uid, request.amountCents, ref, request.itemSlot)
        if (reason != null) { if (_uiState.value === scanning) stopVipScan(reason); return }
        if (_uiState.value !== scanning) {
            // Cancelled meanwhile: give it back.
            val ok = com.goldsky.ssp.payment.VipRepository.refund(uid, ref)
            if (ok) unjournal(ref) else record(ref, "PAID", "FAILED")
            return
        }
        vipUid = uid
        vipBalanceAfter = balance
        dispense(ref, request.amountCents, request.itemSlot)
    }

    /** Card first: a session on the balance -- nothing taken until an item is chosen. */
    private suspend fun openVipSession(uid: String, scanning: VendingState.Scanning) {
        val card = com.goldsky.ssp.payment.VipRepository.getVipCard(uid)
        if (_uiState.value !== scanning) return
        if (card == null || !card.is_active) { stopVipScan(VendingState.Reason.VIP_INVALID); return }
        if (card.balance_cents <= 0) { stopVipScan(VendingState.Reason.VIP_LOW_BALANCE); return }
        val limit = card.balance_cents.coerceAtMost(VIP_SESSION_MAX_CENTS)
        vipUid = uid
        vipSession = true
        mdbController.beginSession(limit)
        _uiState.value = VendingState.SessionOpen(limit, System.currentTimeMillis() + SESSION_MS, card.balance_cents)
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            delay(SESSION_MS)
            if (_uiState.value is VendingState.SessionOpen) endSession(VendingState.Reason.NO_SELECTION)
        }
    }

    /** A VIP session's selection: its price off the balance, then the machine vends. */
    private fun payVipSessionItem(amount: Int, slot: String) {
        val uid = vipUid ?: return endSession(VendingState.Reason.VIP_INVALID)
        timerJob?.cancel()
        val ref = newRef()
        val session = _uiState.value
        viewModelScope.launch {
            val (balance, reason) = deductVip(uid, amount, ref, slot)
            if (_uiState.value !== session) {
                // The session ended meanwhile (cancelled on the machine, timed out): give it back.
                if (reason == null) {
                    if (com.goldsky.ssp.payment.VipRepository.refund(uid, ref)) unjournal(ref) else record(ref, "PAID", "FAILED")
                }
                return@launch
            }
            if (reason != null) { endSession(reason); return@launch }
            vipBalanceAfter = balance
            dispense(ref, amount, slot)
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
        val vip = vipUid
        // After VEND FAILURE too the machine ends the session (SESSION COMPLETE -> END SESSION).
        if (vip != null) {
            // Paid from the balance: it goes back to the card.
            viewModelScope.launch { done(simulated || com.goldsky.ssp.payment.VipRepository.refund(vip, txnId)) }
        } else if (hold != null) {
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
        vipUid = null
        vipSession = false
        vipBalanceAfter = null
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
            "vip" -> startVipScan()
        }
    }
}
