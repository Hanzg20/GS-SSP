package com.goldsky.ssp.vending

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MVI-style ViewModel for Vending Machine logic on IM25.
 * States are driven by MDB hardware events or UI interactions.
 */
class VendingViewModel : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        data class AwaitingPayment(val amountCents: Int, val productLabel: String) : UiState()
        object Authorizing : UiState()
        object Dispensing : UiState()
        data class Success(val orderId: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Triggered when MDB VMC sends a VEND REQUEST.
     */
    fun onMdbVendRequest(amountCents: Int, itemNumber: String) {
        _uiState.value = UiState.AwaitingPayment(amountCents, "Item $itemNumber")
    }

    /**
     * Triggered when user taps card and POSLink starts processing.
     */
    fun onPaymentInitiated() {
        _uiState.value = UiState.Authorizing
    }

    /**
     * Triggered when payment is approved and we tell the machine to dispense.
     */
    fun onPaymentApproved() {
        _uiState.value = UiState.Dispensing
    }

    /**
     * Final successful closure.
     */
    fun onVendSuccess(orderId: String) {
        _uiState.value = UiState.Success(orderId)
    }

    /**
     * Handle failures (MDB Timeout, Payment Declined, Mechanical Jam).
     */
    fun onError(msg: String) {
        _uiState.value = UiState.Error(msg)
    }

    fun resetToIdle() {
        _uiState.value = UiState.Idle
    }
}
