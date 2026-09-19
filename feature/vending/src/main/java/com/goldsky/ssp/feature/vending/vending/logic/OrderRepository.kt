package com.goldsky.ssp.vending.logic

import android.content.Context
import android.util.Log
import com.goldsky.ssp.vending.db.VendingOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Repository for Vending orders.
 * Features JSON file persistence for resilience and WorkManager sync support.
 */
object OrderRepository {
    private const val TAG = "OrderRepo"
    private const val FILENAME = "orders_v2.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _orders = MutableStateFlow<List<VendingOrder>>(emptyList())
    val orders: StateFlow<List<VendingOrder>> = _orders.asStateFlow()

    fun init(context: Context) {
        val file = File(context.filesDir, FILENAME)
        if (file.exists()) {
            try {
                val content = file.readText()
                val list = json.decodeFromString<List<VendingOrder>>(content)
                _orders.value = list
                Log.i(TAG, "Orders loaded: ${list.size} records")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load orders: ${e.message}")
            }
        }
    }

    private fun saveAll(context: Context) {
        try {
            val content = json.encodeToString(_orders.value)
            File(context.filesDir, FILENAME).writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save orders: ${e.message}")
        }
    }

    fun saveOrder(context: Context, order: VendingOrder) {
        Log.i(TAG, "Saving Order: $order")
        val current = _orders.value.toMutableList()
        current.add(0, order)
        _orders.value = current
        saveAll(context)
    }

    fun updateOrder(context: Context, txnId: String, dispenseStatus: String, paymentStatus: String) {
        val current = _orders.value.toMutableList()
        val index = current.indexOfFirst { it.orderId == txnId }
        if (index != -1) {
            current[index] = current[index].copy(
                dispenseStatus = dispenseStatus,
                paymentStatus = paymentStatus
            )
            _orders.value = current
            saveAll(context)
            Log.i(TAG, "Updated Order $txnId: $dispenseStatus / $paymentStatus")
        }
    }

    /**
     * Called by SyncWorker to mark an order as synced to cloud.
     */
    fun markAsSynced(context: Context, txnId: String) {
        val current = _orders.value.toMutableList()
        val index = current.indexOfFirst { it.orderId == txnId }
        if (index != -1) {
            current[index] = current[index].copy(cloudSynced = true)
            _orders.value = current
            saveAll(context)
        }
    }

    fun getUnsyncedOrders(): List<VendingOrder> {
        return _orders.value.filter { !it.cloudSynced && it.dispenseStatus != "PENDING" }
    }
}
