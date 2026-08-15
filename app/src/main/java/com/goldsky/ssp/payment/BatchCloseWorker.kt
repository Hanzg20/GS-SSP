package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker that performs the daily transaction batch settlement.
 * Critical for ensuring merchant funds are processed and deposited.
 */
class BatchCloseWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i("BatchCloseWorker", "Starting scheduled batch close...")
        
        val vendor = DeviceRepository.getPersistedHardwareVendor()
        val provider = HardwareFactory.getPaymentProvider(applicationContext, vendor)
        val deferred = CompletableDeferred<Result>()

        provider.closeBatch(object : IPaymentProvider.PaymentCallback {
            override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                Log.i("BatchCloseWorker", "Batch closed successfully: $authCode")
                // Record maintenance action for audit
                val sn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN"
                DiagnosticManager.recordMaintenance(sn, "AUTO_BATCH_CLOSE")
                deferred.complete(Result.success())
            }

            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                Log.e("BatchCloseWorker", "Batch close failed: $errorMsg")
                deferred.complete(Result.retry())
            }

            override fun onProgress(message: String) {
                Log.d("BatchCloseWorker", "Batch progress: $message")
            }
        })

        deferred.await()
    }
}
