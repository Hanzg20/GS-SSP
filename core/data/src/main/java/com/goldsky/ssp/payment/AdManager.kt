package com.goldsky.ssp.payment

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.goldsky.ssp.model.AdMedia
import com.goldsky.ssp.model.TargetedAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages local ad playlist and disk quota.
 */
object AdManager {
    private const val AD_PLAYLIST_CACHE = "ad_playlist.json"
    private const val AD_SYNC_WORK = "ad_sync_work"
    private const val HEARTBEAT_WORK = "heartbeat_work"
    private const val TRANSACTION_REPLAY_WORK = "transaction_replay_work"
    private const val BATCH_CLOSE_WORK = "batch_close_work"
    private const val SETTLE_HOUR = 3
    private const val SETTLE_MINUTE = 30

    /** Millis from now until the next local HH:MM (tomorrow if already past). */
    internal fun millisUntilNext(hour: Int, minute: Int, now: java.util.Calendar = java.util.Calendar.getInstance()): Long {
        val next = (now.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (!after(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }
    private const val STORAGE_CLEAN_WORK = "storage_clean_work"
    private val json = Json { ignoreUnknownKeys = true }

    private val _playlistUpdateFlow = MutableSharedFlow<List<TargetedAd>>(replay = 1)
    val playlistUpdateFlow = _playlistUpdateFlow.asSharedFlow()

    /**
     * Schedules every terminal's background jobs (heartbeat, offline
     * transaction replay, daily batch close, storage cleaning) plus ad
     * playlist sync. [syncAds] = false for shells with no ad screen (Aegis
     * Timer) -- the rest is still required there, batch close especially.
     */
    fun init(context: Context, syncAds: Boolean = true) {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 1. Ad Sync (Every 2 hours)
        if (syncAds) {
            val syncRequest = PeriodicWorkRequestBuilder<AdSyncWorker>(2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                AD_SYNC_WORK,
                ExistingPeriodicWorkPolicy.REPLACE, // Change to REPLACE to apply new constraints
                syncRequest
            )
        }

        // 2. Heartbeat (Every 15 mins - WorkManager minimum)
        val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEARTBEAT_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            heartbeatRequest
        )

        // 3. Offline transaction replay
        val replayRequest = PeriodicWorkRequestBuilder<TransactionReplayWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TRANSACTION_REPLAY_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            replayRequest
        )

        // 4. Batch Close -- daily at SETTLE_HOUR:SETTLE_MINUTE local time.
        // Was a bare 24h period anchored to whenever the app first ran, so a
        // settle could land mid-day and contend with a customer's payment for
        // the payment channel. Re-enqueued on every start so the next run is
        // always recomputed to the next 03:30 (KEEP would never move an
        // already-installed terminal's old schedule).
        val batchRequest = PeriodicWorkRequestBuilder<BatchCloseWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(millisUntilNext(SETTLE_HOUR, SETTLE_MINUTE), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BATCH_CLOSE_WORK,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            batchRequest
        )

        // 5. Storage Self-Cleaning (Weekly)
        val cleanRequest = PeriodicWorkRequestBuilder<StorageCleaningWorker>(7, TimeUnit.DAYS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            STORAGE_CLEAN_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            cleanRequest
        )
    }

    fun getAdsDir(context: Context): File {
        val dir = File(context.filesDir, "ads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCachedPlaylist(context: Context): List<TargetedAd> {
        return try {
            val file = File(context.filesDir, AD_PLAYLIST_CACHE)
            if (file.exists()) {
                json.decodeFromString<List<TargetedAd>>(file.readText())
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePlaylist(context: Context, ads: List<TargetedAd>) {
        try {
            val file = File(context.filesDir, AD_PLAYLIST_CACHE)
            file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(TargetedAd.serializer()), ads))
            
            // Notify active AdActivity to reload rules/list
            CoroutineScope(Dispatchers.IO).launch {
                _playlistUpdateFlow.emit(ads)
            }
        } catch (e: Exception) {
            android.util.Log.e("AdManager", "Failed to save playlist: ${e.message}")
        }
    }
}
