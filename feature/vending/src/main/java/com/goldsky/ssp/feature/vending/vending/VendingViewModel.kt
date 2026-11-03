package com.goldsky.ssp.vending

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.goldsky.ssp.DeviceAdapter
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
 * Card payment is NOT wired to a real acquirer yet. Until it is:
 *  - on real hardware every terminal shows [VendingState.Unavailable] and denies
 *    each vend request -- never a free item, never a fake sale;
 *  - payment is simulated only off the Q3mini in a debuggable build (emulator, to
 *    work on the screens), driven by adb (see MainActivity), and a simulated
 *    vend records nothing: no order, no inventory change, no sync.
 */
class VendingViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<VendingState>(VendingState.Idle)
    val uiState: StateFlow<VendingState> = _uiState.asStateFlow()

    private val _french = MutableStateFlow(false)
    val french: StateFlow<Boolean> = _french.asStateFlow()

    // Real hardware only on Q3mini UPT (per docs/wizarpos_upt_integration_spec.md's
    // own "机型检测" guidance); every other model/dev-off-device path keeps using
    // the simulator, same as before this switch existed.
    private val mdbController: IMdbController =
        if (DeviceAdapter.getModel() == DeviceAdapter.HardwareModel.WIZARPOS_Q3MINI) {
            WizarPosMdbController(application)
        } else {
            MockMdbController()
        }

    val simulated = mdbController is MockMdbController &&
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private var timerJob: Job? = null
    private var resultJob: Job? = null
    private var declineNext = false

    companion object {
        private const val TAG = "VendingVM"
        private const val DISPENSE_TIMEOUT_MS = 30_000L
        /** How long a card-first customer has to choose on the machine. */
        const val SESSION_MS = 60_000L
        /** The card-first hold; an item above it is refused. */
        const val SESSION_LIMIT_CENTS = 1000
        private const val RESULT_MS = 6_000L
    }

    init {
        // Initialize local storage
        InventoryManager.init(application)
        OrderRepository.init(application)

        setupMdbListeners()
        if (!simulated) {
            _uiState.value = VendingState.Unavailable
        } else {
            triggerSync()
        }
    }

    private fun triggerSync() {
        val syncRequest = OneTimeWorkRequestBuilder<VendingSyncWorker>().build()
        WorkManager.getInstance(getApplication()).enqueue(syncRequest)
    }

    fun toggleLanguage() {
        _french.value = !_french.value
    }

    private fun setupMdbListeners() {
        mdbController.initialize()

        mdbController.onVendRequest { amount, slot ->
            when (val s = _uiState.value) {
                VendingState.Unavailable -> mdbController.denyVend()
                is VendingState.SessionOpen -> when {
                    InventoryManager.isSoldOut(slot) -> deny(VendingState.Reason.SOLD_OUT, voidHold = false)
                    amount > s.limitCents -> deny(VendingState.Reason.OVER_LIMIT, voidHold = false)
                    // Already authorized: the hold covers it, dispense now.
                    else -> dispense("SIM-${System.currentTimeMillis()}", amount, slot)
                }
                VendingState.Idle, is VendingState.NotCharged, is VendingState.Success -> {
                    resultJob?.cancel()
                    if (InventoryManager.isSoldOut(slot)) {
                        mdbController.denyVend()
                        showResult(VendingState.NotCharged(VendingState.Reason.SOLD_OUT))
                    } else {
                        _uiState.value = VendingState.VendRequestReceived(amount, slot)
                    }
                }
                else -> mdbController.denyVend()
            }
        }

        mdbController.onVendSuccess {
            val currentState = _uiState.value
            if (currentState is VendingState.Dispensing) {
                timerJob?.cancel()
                // Capture the hold / keep the sale at the item's price here once payment is real.
                if (!simulated) {
                    InventoryManager.decrement(getApplication(), currentState.itemSlot)
                    updateOrderStatus(currentState.transactionId, "SUCCESS", "PAID")
                    triggerSync()
                }
                showResult(VendingState.Success(currentState.transactionId, currentState.amountCents, currentState.itemSlot))
            }
        }

        mdbController.onVendFailure { code ->
            val currentState = _uiState.value
            if (currentState is VendingState.Dispensing) {
                timerJob?.cancel()
                performAutoVoid(currentState.transactionId, "MDB_FAILURE_$code")
            }
        }
    }

    /** Card first: START touched on the idle screen. */
    fun startSession() {
        if (_uiState.value != VendingState.Idle || !simulated) return
        _uiState.value = VendingState.Authorizing(null)
        viewModelScope.launch {
            delay(1800)
            if (_uiState.value != VendingState.Authorizing(null)) return@launch
            if (takeDecline()) {
                showResult(VendingState.NotCharged(VendingState.Reason.DECLINED))
                return@launch
            }
            val deadline = System.currentTimeMillis() + SESSION_MS
            _uiState.value = VendingState.SessionOpen(SESSION_LIMIT_CENTS, deadline)
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                delay(SESSION_MS)
                if (_uiState.value is VendingState.SessionOpen) {
                    // Void the hold here once payment is real.
                    showResult(VendingState.NotCharged(VendingState.Reason.NO_SELECTION))
                }
            }
        }
    }

    /** Item first: PAY touched on the price screen. */
    fun startPayment() {
        val currentState = _uiState.value
        if (currentState !is VendingState.VendRequestReceived || !simulated) return
        _uiState.value = VendingState.Authorizing(currentState.amountCents)
        viewModelScope.launch {
            delay(1800)
            if (_uiState.value !is VendingState.Authorizing) return@launch
            if (takeDecline()) {
                deny(VendingState.Reason.DECLINED, voidHold = false)
                return@launch
            }
            dispense("SIM-${System.currentTimeMillis()}", currentState.amountCents, currentState.itemSlot)
        }
    }

    /** CANCEL on the session or price screen. */
    fun cancel() {
        when (_uiState.value) {
            is VendingState.SessionOpen -> deny(VendingState.Reason.CANCELLED, voidHold = true)
            is VendingState.VendRequestReceived -> deny(VendingState.Reason.CANCELLED, voidHold = false)
            else -> {}
        }
    }

    private fun deny(reason: VendingState.Reason, voidHold: Boolean) {
        timerJob?.cancel()
        mdbController.denyVend()
        // Void the hold here once payment is real (voidHold).
        Log.i(TAG, "Vend denied: $reason (void hold: $voidHold)")
        showResult(VendingState.NotCharged(reason))
    }

    private fun dispense(txnId: String, amount: Int, slot: String) {
        if (!simulated) {
            // 1. Write PENDING record to local repository
            OrderRepository.saveOrder(getApplication(), VendingOrder(
                orderId = txnId,
                amountCents = amount,
                slot = slot,
                paymentStatus = "PAID",
                dispenseStatus = "PENDING"
            ))
        }

        // 2. Tell MDB to dispense
        mdbController.approveVend()
        _uiState.value = VendingState.Dispensing(
            transactionId = txnId,
            amountCents = amount,
            itemSlot = slot,
            deadline = System.currentTimeMillis() + DISPENSE_TIMEOUT_MS
        )

        // 3. Start Auto-Void timer
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            delay(DISPENSE_TIMEOUT_MS)
            val currentState = _uiState.value
            if (currentState is VendingState.Dispensing && currentState.transactionId == txnId) {
                performAutoVoid(txnId, "TIMEOUT")
            }
        }
    }

    private fun performAutoVoid(txnId: String, reason: String) {
        // Void the sale / hold here once payment is real.
        Log.w(TAG, "Auto-void $txnId: $reason")
        if (!simulated) {
            updateOrderStatus(txnId, "FAILED", "VOIDED")
            triggerSync()
        }
        showResult(VendingState.NotCharged(VendingState.Reason.VEND_FAILED))
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

    private fun takeDecline(): Boolean = declineNext.also { declineNext = false }

    private fun updateOrderStatus(txnId: String, dispenseStatus: String, paymentStatus: String) {
        OrderRepository.updateOrder(getApplication(), txnId, dispenseStatus, paymentStatus)
    }

    /**
     * Simulator commands from adb (debuggable build, off the Q3mini only -- see
     * MainActivity): "select:<cents>:<item>" = the machine asks for a price,
     * "fail" = the next vend jams, "decline" = the next card is declined.
     */
    fun simulate(command: String) {
        if (!simulated) return
        val parts = command.split(":")
        when (parts[0]) {
            "select" -> (mdbController as MockMdbController).simulateMachineSelection(
                parts.getOrNull(1)?.toIntOrNull() ?: 175, parts.getOrNull(2) ?: "14")
            "fail" -> mdbController.setFailNext(true)
            "decline" -> declineNext = true
            "start" -> startSession()
            "pay" -> startPayment()
        }
    }
}
