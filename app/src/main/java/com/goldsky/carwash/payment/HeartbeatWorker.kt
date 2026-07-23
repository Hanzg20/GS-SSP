package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goldsky.carwash.serial.SerialPortManager
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Heartbeat(
    val device_sn: String, // must match the heartbeats table column name exactly (PostgREST rejects unknown columns)
    val is_serial_ok: Boolean,
    val storage_free_mb: Long,
    val network_type: String
)

class HeartbeatWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val sn = getSn()
            val heartbeat = Heartbeat(
                device_sn = sn,
                is_serial_ok = SerialPortManager.isOpened(),
                storage_free_mb = getFreeSpace(),
                network_type = "WIFI" // Simplified
            )

            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest["heartbeats"].insert(heartbeat)
            Result.success()
        } catch (e: Exception) {
            Log.e("HeartbeatWorker", "Failed to send heartbeat: ${e.message}")
            Result.retry()
        }
    }

    private fun getSn(): String {
        return DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN_SN"
    }

    private fun getFreeSpace(): Long {
        return File(applicationContext.filesDir.absolutePath).freeSpace / (1024 * 1024)
    }
}
