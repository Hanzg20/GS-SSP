package com.goldsky.carwash.payment

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
                    "update_hw" -> {
                        val ref = op.ecrRefNum
                        val status = op.status
                        if (ref != null && status != null) {
                            TransactionRepository.updateHardwareStatusRemote(ref, status)
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
