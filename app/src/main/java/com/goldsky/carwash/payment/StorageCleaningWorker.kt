package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Maintenance worker to keep terminal storage lean.
 * Deletes old logs and ensures no ghost media files persist.
 */
class StorageCleaningWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i("StorageCleaning", "Starting storage maintenance...")

            // 1. Clean old logs (keep last 7 days)
            val logDir = File(applicationContext.filesDir, "logs")
            if (logDir.exists()) {
                val now = System.currentTimeMillis()
                val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
                logDir.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > sevenDaysMs) {
                        file.delete()
                        Log.d("StorageCleaning", "Deleted old log: ${file.name}")
                    }
                }
            }

            // 2. Clear Ktor cache or other temporary files if any
            applicationContext.cacheDir.deleteRecursively()
            applicationContext.cacheDir.mkdirs()

            Log.i("StorageCleaning", "Storage maintenance complete.")
            Result.success()
        } catch (e: Exception) {
            Log.e("StorageCleaning", "Maintenance failed: ${e.message}")
            Result.failure()
        }
    }
}
