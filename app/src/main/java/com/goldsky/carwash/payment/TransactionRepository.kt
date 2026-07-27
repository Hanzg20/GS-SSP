package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class TransactionRecord(
    val device_sn: String,
    val amount: Int,
    val payment_status: String,
    val hardware_status: String? = null,
    val auth_code: String? = null,
    val ecr_ref_num: String? = null,
    val currency: String = "USD",
    // 'CREDIT_CARD' | 'VIP_CARD' | 'QR_CODE' | 'COUPON'. Set on this row's
    // one INSERT (initCardPayment's PENDING pre-write, or
    // startFinalizationSequence's insert branch for VIP/QR/free-wash) --
    // never patched in by a later UPDATE.
    val payment_method: String? = null,
    val product_id: String? = null
)

/**
 * Handles recording financial transactions and hardware outcomes to Supabase.
 * Writes that fail (offline / network error) fall into [OfflineQueueManager]
 * and are replayed by TransactionReplayWorker once connectivity returns.
 */
object TransactionRepository {
    private const val TAG = "TransactionRepository"

    /**
     * Records a new transaction entry, queueing it for later replay on failure.
     */
    suspend fun recordTransaction(context: Context, record: TransactionRecord): Boolean = withContext(Dispatchers.IO) {
        val ok = recordTransactionRemote(record)
        if (!ok) {
            OfflineQueueManager.enqueue(context.filesDir, PendingOp(type = "insert", record = record))
            Log.w(TAG, "Transaction queued offline: ${record.ecr_ref_num}")
        }
        ok
    }

    /**
     * Updates the hardware execution status for an existing transaction,
     * queueing it for later replay on failure.
     */
    suspend fun updateHardwareStatus(context: Context, ecrRefNum: String, status: String): Boolean =
        withContext(Dispatchers.IO) {
            val ok = updateHardwareStatusRemote(ecrRefNum, status)
            if (!ok) {
                OfflineQueueManager.enqueue(context.filesDir, PendingOp(type = "update_hw", ecrRefNum = ecrRefNum, status = status))
                Log.w(TAG, "Hardware status update queued offline: $ecrRefNum -> $status")
            }
            ok
        }

    /**
     * Flips payment_status on an existing row (e.g. PENDING -> PAID after
     * bank approval, or PAID -> VOIDED/REFUNDED after a failed hardware
     * ACK), queueing it for later replay on failure. Never inserts -- the
     * row is expected to already exist (ecr_ref_num is UNIQUE), see
     * MainActivity.initCardPayment for the PENDING pre-write.
     */
    suspend fun updatePaymentStatus(context: Context, ecrRefNum: String, status: String): Boolean =
        withContext(Dispatchers.IO) {
            val ok = updatePaymentStatusRemote(ecrRefNum, status)
            if (!ok) {
                OfflineQueueManager.enqueue(context.filesDir, PendingOp(type = "update_status", ecrRefNum = ecrRefNum, status = status))
                Log.w(TAG, "Payment status update queued offline: $ecrRefNum -> $status")
            }
            ok
        }

    /**
     * Raw network write, no offline queueing. Used both by the public API
     * above and by TransactionReplayWorker when draining the queue.
     */
    suspend fun recordTransactionRemote(record: TransactionRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.client.postgrest["transactions"].insert(record)
            Log.i(TAG, "Transaction recorded: ${record.ecr_ref_num}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error recording transaction: ${e.message}")
            false
        }
    }

    suspend fun updateHardwareStatusRemote(ecrRefNum: String, status: String): Boolean = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.client.postgrest["transactions"].update(
                {
                    set("hardware_status", status)
                }
            ) {
                filter {
                    eq("ecr_ref_num", ecrRefNum)
                }
            }
            Log.i(TAG, "Hardware status updated to $status for $ecrRefNum")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating status: ${e.message}")
            false
        }
    }

    suspend fun updatePaymentStatusRemote(ecrRefNum: String, status: String): Boolean = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.client.postgrest["transactions"].update(
                {
                    set("payment_status", status)
                }
            ) {
                filter {
                    eq("ecr_ref_num", ecrRefNum)
                }
            }
            Log.i(TAG, "Payment status updated to $status for $ecrRefNum")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating payment status: ${e.message}")
            false
        }
    }
}
