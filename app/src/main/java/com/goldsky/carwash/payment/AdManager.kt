package com.goldsky.carwash.payment

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.goldsky.carwash.model.AdMedia
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
    private val json = Json { ignoreUnknownKeys = true }

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
            ExistingPeriodicWorkPolicy.KEEP,
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

        // 3. Offline transaction replay (Every 15 mins - WorkManager minimum).
        // Safety net on top of the immediate retry already attempted inline
        // when a transaction write fails; catches the case where the app was
        // killed/rebooted before connectivity returned.
        val replayRequest = PeriodicWorkRequestBuilder<TransactionReplayWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TRANSACTION_REPLAY_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            replayRequest
        )
    }

    fun getAdsDir(context: Context): File {
        val dir = File(context.filesDir, "ads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCachedPlaylist(context: Context): List<AdMedia> {
        return try {
            val file = File(context.filesDir, AD_PLAYLIST_CACHE)
            if (file.exists()) {
                json.decodeFromString<List<AdMedia>>(file.readText())
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePlaylist(context: Context, ads: List<AdMedia>) {
        try {
            val file = File(context.filesDir, AD_PLAYLIST_CACHE)
            file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AdMedia.serializer()), ads))
        } catch (e: Exception) {
            android.util.Log.e("AdManager", "Failed to save playlist: ${e.message}")
        }
    }
}
