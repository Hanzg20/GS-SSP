package com.goldsky.carwash.payment

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.goldsky.carwash.model.AdMedia
import com.goldsky.carwash.model.TargetedAd
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
    private const val STORAGE_CLEAN_WORK = "storage_clean_work"
    private val json = Json { ignoreUnknownKeys = true }

    private val _playlistUpdateFlow = MutableSharedFlow<List<TargetedAd>>(replay = 1)
    val playlistUpdateFlow = _playlistUpdateFlow.asSharedFlow()

    fun init(context: Context) {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 1. Ad Sync (Every 2 hours)
        val syncRequest = PeriodicWorkRequestBuilder<AdSyncWorker>(2, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AD_SYNC_WORK,
            ExistingPeriodicWorkPolicy.REPLACE, // Change to REPLACE to apply new constraints
            syncRequest
        )

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

        // 4. Batch Close (Daily - Every 24 hours)
        val batchRequest = PeriodicWorkRequestBuilder<BatchCloseWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BATCH_CLOSE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
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
