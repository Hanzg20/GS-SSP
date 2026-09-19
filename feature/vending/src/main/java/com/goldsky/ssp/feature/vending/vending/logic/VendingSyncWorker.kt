package com.goldsky.ssp.vending.logic

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.ShadowManager
import com.goldsky.ssp.payment.TransactionRecord
import com.goldsky.ssp.payment.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker for Vending specific data synchronization.
 * Drains unsynced local orders and updates the cloud device shadow with inventory levels.
 */
class VendingSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val TAG = "VendingSyncWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val deviceSn = DeviceRepository.getPersistedDeviceSn() ?: "IM25-VEND-MOCK"
            
            // 1. Sync Inventory Shadow
            syncInventory(deviceSn)

            // 2. Sync Unsynced Orders
            syncOrders(deviceSn)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync work failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun syncInventory(sn: String) {
        val stock = InventoryManager.getAllStock()
        ShadowManager.syncReportedState(applicationContext, sn, stock)
        Log.d(TAG, "Inventory shadow sync triggered")
    }

    private suspend fun syncOrders(sn: String) {
        val unsynced = OrderRepository.getUnsyncedOrders()
        if (unsynced.isEmpty()) return

        Log.i(TAG, "Attempting to sync ${unsynced.size} orders...")

        unsynced.forEach { order ->
            val record = TransactionRecord(
                device_sn = sn,
                amount = order.amountCents,
                payment_status = order.paymentStatus,
                hardware_status = order.dispenseStatus,
                ecr_ref_num = order.orderId,
                payment_method = "CREDIT_CARD", // Assume credit card for Vending default
                currency = "USD"
            )

            // Try to push to remote. recordTransactionRemote returns true on success.
            val ok = TransactionRepository.recordTransactionRemote(record)
            if (ok) {
                OrderRepository.markAsSynced(applicationContext, order.orderId)
                Log.i(TAG, "Order ${order.orderId} synced successfully")
            } else {
                Log.w(TAG, "Failed to sync order ${order.orderId}")
            }
        }
    }
}
