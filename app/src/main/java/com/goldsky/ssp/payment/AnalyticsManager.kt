package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class AdPlaybackLog(
    val device_sn: String,
    val ad_id: String,
    val started_at: String, // ISO 8601
    val duration_sec: Int,
    val completion_state: String // "COMPLETED" | "INTERRUPTED_BY_USER"
)

/**
 * Handles marketing analytics and proof-of-play reporting.
 */
object AnalyticsManager {
    private const val TAG = "AnalyticsManager"

    /**
     * Records a playback event to the cloud asynchronously.
     */
    fun recordPlayback(
        sn: String,
        adId: String,
        startTime: Long,
        durationMs: Long,
        wasInterrupted: Boolean
    ) {
        val durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)
        val state = if (wasInterrupted) "INTERRUPTED_BY_USER" else "COMPLETED"
        val isoTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(startTime))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val log = AdPlaybackLog(
                    device_sn = sn,
                    ad_id = adId,
                    started_at = isoTime,
                    duration_sec = durationSec,
                    completion_state = state
                )
                SupabaseClientProvider.client.postgrest["ad_playback_logs"].insert(log)
                Log.d(TAG, "Playback recorded for ad $adId ($state, ${durationSec}s)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record playback: ${e.message}")
            }
        }
    }
}
