package com.goldsky.ssp.vending

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.ShadowManager
import com.goldsky.ssp.vending.db.VendingOrder
import com.goldsky.ssp.vending.logic.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MVI-style ViewModel for Vending Machine logic on IM25.
 * State management for the full vend loop: Selection -> Payment -> Dispense.
 * Includes Local Audit logic, Auto-Void, and Inventory reporting.
 */
class VendingViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<VendingState>(VendingState.Idle)
    val uiState: StateFlow<VendingState> = _uiState.asStateFlow()

    private val mdbController: IMdbController = MockMdbController()
    private val deviceSn = DeviceRepository.getPersistedDeviceSn() ?: "IM25-VEND-MOCK"

    private var autoVoidJob: Job? = null
    private val DISPENSE_TIMEOUT_MS = 30000L

    init {
        setupMdbListeners()
        // Initial sync of inventory to cloud shadow
        ShadowManager.syncReportedState(getApplication(), deviceSn, InventoryManager.getAllStock())
    }

    private fun setupMdbListeners() {
        mdbController.initialize()
        
        mdbController.onVendRequest { amount, slot ->
            if (InventoryManager.isSoldOut(slot)) {
                _uiState.value = VendingState.Error("SOLD_OUT", "Product $slot is currently sold out.")
            } else {
                _uiState.value = VendingState.VendRequestReceived(amount, slot)
            }
        }

        mdbController.onVendSuccess {
            val currentState = _uiState.value
            if (currentState is VendingState.Dispensing) {
                autoVoidJob?.cancel()
                
                // 1. Decrement local inventory
                InventoryManager.decrement(currentState.itemSlot)
                
                // 2. Sync updated inventory to cloud
                ShadowManager.syncReportedState(getApplication(), deviceSn, InventoryManager.getAllStock())
                
                OrderRepository.updateOrder(currentState.transactionId, "SUCCESS", "PAID")
                _uiState.value = VendingState.Success(currentState.transactionId)
                
                viewModelScope.launch {
                    delay(3000)
                    _uiState.value = VendingState.Idle
                }
            }
        }

        mdbController.onVendFailure { code ->
            val currentState = _uiState.value
            if (currentState is VendingState.Dispensing) {
                autoVoidJob?.cancel()
                performAutoVoid(currentState.transactionId, "MDB_FAILURE_$code")
            }
        }
    }

    /**
     * Called when the Payment UI confirms the tap.
     */
    fun startPayment() {
        val currentState = _uiState.value
        if (currentState is VendingState.VendRequestReceived) {
            _uiState.value = VendingState.PaymentAuthorizing
            
            // Simulation: approved after 2 seconds
            viewModelScope.launch {
                delay(2000)
                approvePayment(currentState.amountCents, currentState.itemSlot)
            }
        }
    }

    private fun approvePayment(amount: Int, slot: String) {
        val currentState = _uiState.value
        if (currentState is VendingState.PaymentAuthorizing) {
            val txnId = "TXN-${System.currentTimeMillis()}"
            
            // 1. Write PENDING record to local repository
            OrderRepository.saveOrder(VendingOrder(
                orderId = txnId,
                amountCents = amount,
                slot = slot,
                paymentStatus = "PAID",
                dispenseStatus = "PENDING"
            ))

            // 2. Tell MDB to dispense
            mdbController.approveVend()
            _uiState.value = VendingState.Dispensing(
                transactionId = txnId, 
                amountCents = amount, 
                itemSlot = slot,
                deadline = System.currentTimeMillis() + DISPENSE_TIMEOUT_MS
            )

            // 3. Start Auto-Void timer
            startAutoVoidTimer(txnId)
        }
    }

    private fun startAutoVoidTimer(txnId: String) {
        autoVoidJob?.cancel()
        autoVoidJob = viewModelScope.launch {
            delay(DISPENSE_TIMEOUT_MS)
            val currentState = _uiState.value
            if (currentState is VendingState.Dispensing && currentState.transactionId == txnId) {
                performAutoVoid(txnId, "TIMEOUT")
            }
        }
    }

    private fun performAutoVoid(txnId: String, reason: String) {
        // In real app, call POSLink.Void(txnId)
        OrderRepository.updateOrder(txnId, "FAILED", "VOIDED")
        _uiState.value = VendingState.Error(reason, "Dispense Failed. Refund Processed.")
    }

    /**
     * Exposed for the Dev/Debug UI to simulate a machine trigger.
     */
    fun debugTriggerMachineRequest(priceCents: Int = 150, slot: String = "A1") {
        (mdbController as? MockMdbController)?.simulateMachineSelection(priceCents, slot)
    }

    /**
     * Simulation helper to fail the next vend.
     */
    fun debugFailNextVend() {
        mdbController.setFailNext(true)
    }

    fun resetToIdle() {
        _uiState.value = VendingState.Idle
    }
}
