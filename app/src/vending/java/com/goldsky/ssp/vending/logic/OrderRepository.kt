package com.goldsky.ssp.vending.logic

import android.util.Log
import com.goldsky.ssp.vending.db.VendingOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory repository for development to avoid build issues with Room+Gradle 9.5.
 * Provides the same interface for UI development.
 */
object OrderRepository {
    private val _orders = MutableStateFlow<List<VendingOrder>>(emptyList())
    val orders: StateFlow<List<VendingOrder>> = _orders.asStateFlow()

    fun saveOrder(order: VendingOrder) {
        Log.i("OrderRepo", "Saving Order: $order")
        val current = _orders.value.toMutableList()
        current.add(0, order)
        _orders.value = current
    }

    fun updateOrder(txnId: String, dispenseStatus: String, paymentStatus: String) {
        val current = _orders.value.toMutableList()
        val index = current.indexOfFirst { it.orderId == txnId }
        if (index != -1) {
            current[index] = current[index].copy(
                dispenseStatus = dispenseStatus,
                paymentStatus = paymentStatus
            )
            _orders.value = current
            Log.i("OrderRepo", "Updated Order $txnId: $dispenseStatus / $paymentStatus")
        }
    }
}
