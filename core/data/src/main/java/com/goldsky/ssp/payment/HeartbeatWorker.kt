package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goldsky.ssp.common.VoiceVolume
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
    val network_type: String,
    /** Kiosk screen in front; null = not a kiosk app ([KioskFront]). */
    val kiosk_in_front: Boolean? = null,
    /** Voice prompt volume and its max ([VoiceVolume]); null = not read. */
    val voice_volume: Int? = null,
    val voice_volume_max: Int? = null
)

class HeartbeatWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        if (send(applicationContext)) Result.success() else Result.retry()

    companion object {
        /** When this process last delivered a heartbeat (0 = not yet); for diagnostics screens. */
        @Volatile var lastSentAt = 0L
            private set

        /**
         * Sends one heartbeat now; true when it reached Supabase. Shared by the
         * worker and [AdManager]'s on-network-available path, which calls it
         * directly -- a one-time WorkManager job only ran ~45 s after the
         * network came back.
         */
        suspend fun send(context: Context): Boolean = withContext(Dispatchers.IO) {
            try {
                val vendor = DeviceRepository.getPersistedHardwareVendor()
                val volume = VoiceVolume.read(context)
                val heartbeat = Heartbeat(
                    device_sn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN_SN",
                    // No provider registered (e.g. a cold process before the shell wired one) is "not ok", not a failed heartbeat.
                    is_serial_ok = try { HardwareFactory.getSerialProvider(context, vendor).isOpened() } catch (e: Exception) { false },
                    storage_free_mb = File(context.filesDir.absolutePath).freeSpace / (1024 * 1024),
                    network_type = "WIFI", // Simplified
                    kiosk_in_front = KioskFront.forHeartbeat(context),
                    voice_volume = volume?.first,
                    voice_volume_max = volume?.second
                )
                SupabaseClientProvider.ensureAuthenticated()
                try {
                    SupabaseClientProvider.client.postgrest["heartbeats"].insert(heartbeat)
                } catch (e: Exception) {
                    // Database without docs/migrations/2026-10-22_voice_volume.sql yet: PostgREST
                    // rejects the unknown column. Null fields at their default aren't sent.
                    if (e.message?.contains("voice_volume") != true) throw e
                    Log.w("HeartbeatWorker", "Heartbeat without the volume (column missing)")
                    SupabaseClientProvider.client.postgrest["heartbeats"].insert(heartbeat.copy(voice_volume = null, voice_volume_max = null))
                }
                lastSentAt = System.currentTimeMillis()
                true
            } catch (e: Exception) {
                Log.e("HeartbeatWorker", "Failed to send heartbeat: ${e.message}")
                false
            }
        }
    }
}
