package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodically drains OfflineQueueManager, replaying transaction writes that
 * failed while the device was offline. Scheduled alongside the ad-sync /
 * heartbeat workers in AdManager.init.
 */
class TransactionReplayWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            OfflineQueueManager.drain(applicationContext.filesDir) { op ->
                when (op.type) {
                    "insert" -> op.record?.let { TransactionRepository.recordTransactionRemote(it) } ?: true
                    // Queued after its "insert", so the parent row lands first (FK on ecr_ref_num).
                    "insert_items" -> op.items?.let { TransactionRepository.recordItemsRemote(it) } ?: true
                    // A refusal (false) is final -- only a transport failure (null) stays queued.
                    "delivery_paid" -> {
                        val id = op.orderId
                        val ref = op.ecrRefNum
                        if (id != null && ref != null) {
                            DeliveryOrderRepository.markPaidRemote(id, ref, op.paymentMethod ?: "UNKNOWN") != null
                        } else {
                            true // malformed entry, drop it rather than retry forever
                        }
                    }
                    "dine_payment" -> {
                        val id = op.orderId
                        val ref = op.ecrRefNum
                        val amount = op.amountCents
                        if (id != null && ref != null && amount != null) {
                            DineRepository.recordPaymentRemote(id, ref, amount, op.tipCents ?: 0, op.paymentMethod, op.paidBy, op.itemIds) != null
                        } else {
                            true // malformed entry, drop it rather than retry forever
                        }
                    }
                    "refund" -> {
                        val ref = op.ecrRefNum
                        val status = op.status
                        if (ref != null && status != null) {
                            TransactionRepository.recordRefundRemote(ref, status, op.approverId, op.approverName, op.refundedAt)
                        } else {
                            true // malformed entry, drop it rather than retry forever
                        }
                    }
                    "update_hw" -> {
                        val ref = op.ecrRefNum
                        val status = op.status
                        if (ref != null && status != null) {
                            TransactionRepository.updateHardwareStatusRemote(ref, status)
                        } else {
                            true // malformed entry, drop it rather than retry forever
                        }
                    }
                    "update_status" -> {
                        val ref = op.ecrRefNum
                        val status = op.status
                        if (ref != null && status != null) {
                            // Iris used to queue "FAILED", which payment_status doesn't
                            // allow -- those rows sat PENDING forever. Land them as DECLINED.
                            val landable = if (status == "FAILED") "DECLINED" else status
                            TransactionRepository.updatePaymentStatusRemote(ref, landable, op.entryMode, op.paymentMethod, op.cardAid, op.cardBin, op.cardBrand, op.clearPaymentMethod, op.authCode)
                        } else {
                            true // malformed entry, drop it rather than retry forever
                        }
                    }
                    else -> true // unknown op type, drop it
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("TransactionReplayWorker", "Replay worker failed: ${e.message}")
            Result.retry()
        }
    }
}
