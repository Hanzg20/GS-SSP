package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goldsky.ssp.payment.hardware.HardwareFactory
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

    override suspend fun doWork(): Result =
        if (send(applicationContext)) Result.success() else Result.retry()

    companion object {
        /**
         * Sends one heartbeat now; true when it reached Supabase. Shared by the
         * worker and [AdManager]'s on-network-available path, which calls it
         * directly -- a one-time WorkManager job only ran ~45 s after the
         * network came back.
         */
        suspend fun send(context: Context): Boolean = withContext(Dispatchers.IO) {
            try {
                val vendor = DeviceRepository.getPersistedHardwareVendor()
                val heartbeat = Heartbeat(
                    device_sn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN_SN",
                    // No provider registered (e.g. a cold process before the shell wired one) is "not ok", not a failed heartbeat.
                    is_serial_ok = try { HardwareFactory.getSerialProvider(context, vendor).isOpened() } catch (e: Exception) { false },
                    storage_free_mb = File(context.filesDir.absolutePath).freeSpace / (1024 * 1024),
                    network_type = "WIFI" // Simplified
                )
                SupabaseClientProvider.ensureAuthenticated()
                SupabaseClientProvider.client.postgrest["heartbeats"].insert(heartbeat)
                true
            } catch (e: Exception) {
                Log.e("HeartbeatWorker", "Failed to send heartbeat: ${e.message}")
                false
            }
        }
    }
}
