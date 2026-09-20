package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.db.OrderEntity
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class TransactionRecord(
    val device_sn: String,
    val amount: Int,
    val subtotal_cents: Int? = null,
    val tax_cents: Int? = null,
    val tip_cents: Int? = null,
    val payment_status: String,
    val hardware_status: String? = null,
    val auth_code: String? = null,
    val ecr_ref_num: String? = null,
    val currency: String = "USD",
    val payment_method: String? = null,
    val product_id: String? = null,
    val entry_mode: String? = null,
    // Set for VIP_CARD payments only; transactions.vip_card_uid (see docs/migrations/2026-09-20_vip_card_ledger.sql)
    val vip_card_uid: String? = null,
    // EMV AID / first 6 PAN digits of a card payment, see CardTypeClassifier
    val card_aid: String? = null,
    val card_bin: String? = null,
    val card_brand: String? = null
)

/**
 * Handles recording financial transactions and hardware outcomes to Supabase.
 * Dual-write strategy: Local Room DB for instant Records UI & Resilience, 
 * Remote Supabase for Cloud audit.
 */
object TransactionRepository {
    private const val TAG = "TransactionRepository"

    /**
     * Records a new transaction entry locally and remotely.
     */
    suspend fun recordTransaction(context: Context, record: TransactionRecord): Boolean = withContext(Dispatchers.IO) {
        // 1. Persist Locally First
        try {
            val db = LocalDatabase.getInstance(context)
            db.orderDao().insert(record.toEntity())
            Log.d(TAG, "Local stub created: ${record.ecr_ref_num}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save local stub: ${e.message}")
        }

        // 2. Try Remote
        val ok = recordTransactionRemote(record)
        if (!ok) {
            OfflineQueueManager.enqueue(context.filesDir, PendingOp(type = "insert", record = record))
            Log.w(TAG, "Transaction queued offline: ${record.ecr_ref_num}")
        }
        ok
    }

    /**
     * Updates hardware status locally and remotely.
     */
    suspend fun updateHardwareStatus(context: Context, ecrRefNum: String, status: String): Boolean =
        withContext(Dispatchers.IO) {
            // Update local
            try {
                val db = LocalDatabase.getInstance(context)
                val all = db.orderDao().getAll()
                all.find { it.ecrRefNum == ecrRefNum }?.let {
                    db.orderDao().update(it.copy(hardwareStatus = status))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local HW update failed: ${e.message}")
            }

            val ok = updateHardwareStatusRemote(ecrRefNum, status)
            if (!ok) {
                OfflineQueueManager.enqueue(context.filesDir, PendingOp(type = "update_hw", ecrRefNum = ecrRefNum, status = status))
                Log.w(TAG, "Hardware status update queued offline: $ecrRefNum -> $status")
            }
            ok
        }

    /**
     * Flips payment_status locally and remotely.
     */
    suspend fun updatePaymentStatus(
        context: Context,
        ecrRefNum: String,
        status: String,
        entryMode: String? = null,
        // Card payments only: refines the PENDING row's provisional CREDIT_CARD once the terminal reports the real card.
        paymentMethod: String? = null,
        cardAid: String? = null,
        cardBin: String? = null,
        cardBrand: String? = null
    ): Boolean =
        withContext(Dispatchers.IO) {
            // Update local
            try {
                val db = LocalDatabase.getInstance(context)
                val all = db.orderDao().getAll()
                all.find { it.ecrRefNum == ecrRefNum }?.let {
                    db.orderDao().update(it.copy(status = status))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local payment status update failed: ${e.message}")
            }

            val ok = updatePaymentStatusRemote(ecrRefNum, status, entryMode, paymentMethod, cardAid, cardBin, cardBrand)
            if (!ok) {
                OfflineQueueManager.enqueue(
                    context.filesDir,
                    PendingOp(
                        type = "update_status", ecrRefNum = ecrRefNum, status = status, entryMode = entryMode,
                        paymentMethod = paymentMethod, cardAid = cardAid, cardBin = cardBin, cardBrand = cardBrand
                    )
                )
                Log.w(TAG, "Payment status update queued offline: $ecrRefNum -> $status")
            }
            ok
        }

    /**
     * Pulls all local orders for the Records UI.
     */
    suspend fun getAllLocal(context: Context): List<OrderEntity> = withContext(Dispatchers.IO) {
        try {
            LocalDatabase.getInstance(context).orderDao().getAll()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun TransactionRecord.toEntity() = OrderEntity(
        ecrRefNum = ecr_ref_num ?: "ERR_${System.currentTimeMillis()}",
        subtotalCents = subtotal_cents ?: amount,
        taxCents = tax_cents ?: 0,
        tipCents = tip_cents ?: 0,
        amountCents = amount,
        status = payment_status,
        hardwareStatus = hardware_status,
        paymentMethod = payment_method ?: "UNKNOWN"
    )

    /**
     * Raw network write, no offline queueing.
     */
    suspend fun recordTransactionRemote(record: TransactionRecord): Boolean = withContext(Dispatchers.IO) {
        if (CoreConfig.isMock) {
            Log.i(TAG, "MOCK: Transaction recorded (skipped remote)")
            return@withContext true
        }
        try {
            val sanitizedRecord = if (record.product_id != null && !record.product_id.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) {
                // Simplified UUID check for brevity
                record.copy(product_id = null)
            } else {
                record
            }
            SupabaseClientProvider.client.postgrest["transactions"].insert(sanitizedRecord)
            Log.i(TAG, "Transaction recorded: ${record.ecr_ref_num}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error recording transaction: ${e.message}")
            false
        }
    }

    /**
     * UPDATE ... WHERE ecr_ref_num = ? that reports whether a row was actually
     * changed. PostgREST answers 200 with zero rows when RLS hides the row (e.g.
     * the auth session isn't linked to this device in device_auth_map), which
     * used to be logged as success while the transaction stayed PENDING. On zero
     * rows: re-link the session and retry once; still zero -> false, so the
     * caller queues it for replay instead of dropping it.
     */
    private suspend fun updateRows(
        label: String,
        ecrRefNum: String,
        values: io.github.jan.supabase.postgrest.query.PostgrestUpdate.() -> Unit
    ): Boolean {
        repeat(2) { attempt ->
            val result = SupabaseClientProvider.client.postgrest["transactions"].update(values) {
                select()
                filter { eq("ecr_ref_num", ecrRefNum) }
            }
            if (result.data.trim() != "[]") return true
            Log.w(TAG, "$label update matched 0 rows for $ecrRefNum (attempt ${attempt + 1})")
            if (attempt == 0) SupabaseClientProvider.relinkDeviceIdentity()
        }
        return false
    }

    suspend fun updateHardwareStatusRemote(ecrRefNum: String, status: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val updated = updateRows("hardware_status", ecrRefNum) { set("hardware_status", status) }
            if (!updated) return@withContext false
            Log.i(TAG, "Hardware status updated to $status for $ecrRefNum")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating status: ${e.message}")
            false
        }
    }

    suspend fun updatePaymentStatusRemote(
        ecrRefNum: String,
        status: String,
        entryMode: String? = null,
        paymentMethod: String? = null,
        cardAid: String? = null,
        cardBin: String? = null,
        cardBrand: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val updated = updateRows("payment_status", ecrRefNum) {
                set("payment_status", status)
                paymentMethod?.let { set("payment_method", it) }
                cardAid?.let { set("card_aid", it) }
                cardBin?.let { set("card_bin", it) }
                cardBrand?.let { set("card_brand", it) }
            }
            if (!updated) return@withContext false
            Log.i(TAG, "Payment status updated to $status for $ecrRefNum")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating payment status: ${e.message}")
            false
        }
    }
}
