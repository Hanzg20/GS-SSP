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
    private const val AD_SYNC_NOW_WORK = "ad_sync_now"
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

        // 6. Heartbeat (+ offline replay) as soon as the terminal is online:
        // now (runs the moment there is a network), and again every time the
        // network comes back. The periodic heartbeat alone left CMP showing a
        // stale last_seen for up to 15 min after a boot or a Wi-Fi drop.
        onlineNow(context)
        watchNetwork(context.applicationContext)
    }

    private const val HEARTBEAT_NOW_WORK = "heartbeat_now"
    private const val REPLAY_NOW_WORK = "transaction_replay_now"
    private const val ONLINE_NOW_MIN_GAP_MS = 15_000L
    @Volatile private var lastOnlineNowAt = 0L
    @Volatile private var watchingNetwork = false

    /**
     * One heartbeat and one offline-queue replay, as soon as there is a
     * network. The heartbeat is sent directly (a WorkManager one-time job
     * started ~45 s late); only if that fails is it left to a one-time job
     * that runs once the network is really usable. At most once per 15 s, so
     * a flapping Wi-Fi doesn't send a burst; [force] skips that limit (the
     * device was only just registered, so an earlier one may have been refused).
     */
    fun onlineNow(context: Context, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastOnlineNowAt != 0L && now - lastOnlineNowAt < ONLINE_NOW_MIN_GAP_MS) return
        lastOnlineNowAt = now
        val appContext = context.applicationContext
        val online = androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(appContext)
        wm.enqueueUniqueWork(
            REPLAY_NOW_WORK, androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<TransactionReplayWorker>().setConstraints(online).build()
        )
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            // Bounded, and tried again on a fresh connection: a request stuck on
            // a half-dead socket must not hold the heartbeat back.
            var sent = false
            for (attempt in 1..3) {
                if (attempt > 1) SupabaseClientProvider.dropConnections()
                sent = kotlinx.coroutines.withTimeoutOrNull(10_000L) { HeartbeatWorker.send(appContext) } == true
                if (sent) break
                kotlinx.coroutines.delay(2_000L)
            }
            if (sent) {
                android.util.Log.i("AdManager", "Heartbeat sent on coming online")
                wm.cancelUniqueWork(HEARTBEAT_NOW_WORK) // onAvailable's fallback isn't needed any more
            } else {
                lastOnlineNowAt = 0L // offline at the time: let the next network-available try again at once
                enqueueHeartbeatNow(wm)
            }
        }
    }

    private fun enqueueHeartbeatNow(wm: WorkManager) {
        val online = androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniqueWork(
            HEARTBEAT_NOW_WORK, androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<HeartbeatWorker>().setConstraints(online).build()
        )
    }

    /**
     * Calls [onlineNow] whenever a default network becomes VALIDATED (= has
     * working internet). Not onAvailable: that fires before DNS / routing are
     * usable, and on the Q2 a heartbeat sent then hung until it timed out
     * (Wi-Fi off/on, 2026-10-10). onAvailable still drops the old network's
     * pooled connections and queues a WorkManager heartbeat, for a network
     * that never validates (no internet probe allowed). Registered once per process.
     */
    private fun watchNetwork(appContext: Context) {
        if (watchingNetwork) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                @Volatile private var validated: android.net.Network? = null

                override fun onAvailable(network: android.net.Network) {
                    SupabaseClientProvider.dropConnections()
                    enqueueHeartbeatNow(WorkManager.getInstance(appContext))
                }

                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) || network == validated) return
                    validated = network
                    android.util.Log.i("AdManager", "Network online: heartbeat + replay now")
                    onlineNow(appContext)
                }

                override fun onLost(network: android.net.Network) {
                    if (network == validated) validated = null
                }
            })
            watchingNetwork = true
        } catch (e: Exception) {
            android.util.Log.w("AdManager", "Network watch not registered: ${e.message}")
        }
    }

    /**
     * Sync the playlist now instead of at the next 2-hourly run -- on a CMP
     * SYNC_CONFIG (sent after a playlist / ad change). Playlist edits used to
     * wait up to 2 h (bay5 showed 2 of its 6 ads, 2026-10-07).
     */
    fun syncNow(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<AdSyncWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(AD_SYNC_NOW_WORK, androidx.work.ExistingWorkPolicy.REPLACE, request)
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
