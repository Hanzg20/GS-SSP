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
            // No SN yet (first start, before the hardware answered): try again later.
            val deviceSn = DeviceRepository.getPersistedDeviceSn() ?: return@withContext Result.retry()

            // The inventory shadow (syncInventory) stays off until the terminal knows the
            // machine's real columns: InventoryManager only holds placeholder slots.
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

        var anyFailed = false
        unsynced.forEach { order ->
            val record = TransactionRecord(
                device_sn = sn,
                amount = order.amountCents,
                payment_status = order.paymentStatus,
                hardware_status = order.dispenseStatus,
                auth_code = order.authCode,
                ecr_ref_num = order.orderId,
                // Same values as Wash / Timer: a card sale, or paid from a VIP card's balance.
                payment_method = if (order.vipCardUid != null) "VIP_CARD" else "CREDIT_CARD",
                vip_card_uid = order.vipCardUid,
                entry_mode = order.entryMode,
                card_brand = order.cardBrand,
                card_aid = order.cardAid,
                card_bin = order.cardBin,
                // currency: the region default (CAD in Canada); this used to say USD.
            )

            // Try to push to remote. recordTransactionRemote returns true on success.
            val ok = TransactionRepository.recordTransactionRemote(record)
            if (ok) {
                OrderRepository.markAsSynced(applicationContext, order.orderId)
                Log.i(TAG, "Order ${order.orderId} synced successfully")
            } else {
                Log.w(TAG, "Failed to sync order ${order.orderId}")
                anyFailed = true
            }
        }
        // Offline: WorkManager retries with backoff instead of leaving the rows for the next vend.
        if (anyFailed) throw IllegalStateException("some orders not synced")
    }
}
