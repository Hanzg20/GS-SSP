package com.goldsky.ssp.vending.logic

/**
 * Formal Finite State Machine states for the Vending process.
 * Highly aligned with MDB/ICP 4.3 standards.
 */
sealed class VendingState {
    object Booting : VendingState()
    object Idle : VendingState()
    
    data class VendRequestReceived(
        val amountCents: Int,
        val itemSlot: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : VendingState()
    
    object PaymentAuthorizing : VendingState()
    
    data class Dispensing(
        val transactionId: String,
        val amountCents: Int,
        val itemSlot: String,
        val deadline: Long // To handle Auto-Void if timeout
    ) : VendingState()
    
    data class Success(val orderId: String) : VendingState()
    
    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean = true
    ) : VendingState()
}

/**
 * Abstract interface to isolate MDB hardware from the UI logic.
 */
interface IMdbController {
    fun initialize()
    fun onVendRequest(callback: (amount: Int, slot: String) -> Unit)
    fun onVendSuccess(callback: () -> Unit)
    fun onVendFailure(callback: (errorCode: Int) -> Unit)
    fun approveVend()
    fun denyVend()
    fun setFailNext(fail: Boolean)
}
