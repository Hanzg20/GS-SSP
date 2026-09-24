package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Daily batch settlement, scheduled for the small hours (see AdManager) so it
 * never competes with a customer's payment for the payment channel. Retries a
 * failed settle up to [MAX_ATTEMPTS] times, then raises a CRITICAL alert --
 * an unsettled batch means the merchant isn't getting paid.
 */
class BatchCloseWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Clear stuck PENDING sales first so the batch we close is final.
        runCatching { PendingResolver.resolve(applicationContext) }
            .onFailure { Log.e("BatchCloseWorker", "Pending resolve failed: ${it.message}") }
        val outcome = SettlementManager.settle(applicationContext, "AUTO")
        if (outcome.ok) return Result.success()
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            Log.e("BatchCloseWorker", "Settle failed $MAX_ATTEMPTS times, giving up until tomorrow: ${outcome.message}")
            val sn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN"
            DiagnosticManager.reportError(sn, "BATCH_CLOSE_GAVE_UP", severity = "CRITICAL", trace = outcome.message)
            return Result.success() // keeps the periodic schedule; tomorrow tries again
        }
        return Result.retry()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
