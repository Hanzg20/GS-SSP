package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goldsky.carwash.model.AdMedia
import com.goldsky.carwash.model.PlaylistEntry
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class AdSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    // Only for the raw media-file downloads below (Supabase Storage URLs,
    // not the data API) -- the advertisements row fetch goes through
    // SupabaseClientProvider.client.postgrest so it carries the device's
    // real session token, not this client.
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i("AdSyncWorker", "Starting Ad Sync...")

            // advertisements' RLS policy is "SELECT TO authenticated" (see
            // docs/supabase_full_schema.sql) -- the previous plain GET below
            // sent the static anon key as its own bearer token, which
            // PostgREST resolves to role=anon (the key's own embedded JWT
            // claim, not something a header choice can override), always
            // getting RLS-filtered to zero rows regardless of how many ads
            // existed. Same dual-identity bug class already fixed for
            // TransactionRepository/VipRepository/etc. via
            // ensureAuthenticated() (see SupabaseClientProvider's doc
            // comment) -- this worker used its own bespoke HttpClient
            // instead of the shared one, so it never got that fix. Verified
            // live: anon role sees 0 rows, authenticated sees the real 2.
            SupabaseClientProvider.ensureAuthenticated()

            // 1. Fetch remote playlist -- curated per device via `playlists`
            // (device_sn -> ad_id, play_order), not a global broadcast of
            // every advertisements row. A device with no persisted SN yet
            // (cold-start race with extractDeviceIdentity()) or no playlist
            // rows assigned bails out via Result.retry()/empty rather than
            // wiping any already-synced local playlist.
            val deviceSn = DeviceRepository.getPersistedDeviceSn()
            if (deviceSn == null) {
                Log.w("AdSyncWorker", "No device SN persisted yet, retrying later")
                return@withContext Result.retry()
            }

            // `playlists`' RLS policy scopes rows to device_sn IN (SELECT
            // device_sn FROM device_auth_map WHERE auth_user_id = auth.uid()),
            // i.e. it depends on sync_device_identity() having already linked
            // this anon session to deviceSn. That linkage is normally
            // established by MainActivity's own startup coroutine, but this
            // worker's periodic first-run can race ahead of it on a cold
            // start (verified: playlists silently returned 0 rows the first
            // time, even with rows present, because device_auth_map's insert
            // hadn't landed yet). Calling it here too is idempotent and
            // removes the dependency on ordering against MainActivity.
            DeviceRepository.syncDeviceIdentity(deviceSn)

            val entries: List<PlaylistEntry> = SupabaseClientProvider.client.postgrest["playlists"]
                .select {
                    filter { eq("device_sn", deviceSn) }
                    order("play_order", Order.ASCENDING)
                }
                .decodeList<PlaylistEntry>()

            val remoteAds: List<AdMedia> = if (entries.isNotEmpty()) {
                val adIds = entries.map { it.ad_id }
                val adsById = SupabaseClientProvider.client.postgrest["advertisements"]
                    .select {
                        filter { isIn("id", adIds) }
                    }
                    .decodeList<AdMedia>()
                    .associateBy { it.id }
                // Re-sort by this device's play_order -- the advertisements
                // fetch above has no ordering guarantee of its own.
                entries.mapNotNull { adsById[it.ad_id] }
            } else {
                // GLOBAL FALLBACK: Fetch all active advertisements if no specific
                // playlist exists for this device's SN. Ensures new/unprovisioned
                // machines don't stay on a static placeholder.
                Log.i("AdSyncWorker", "No specific playlist found for $deviceSn, falling back to all available ads")
                SupabaseClientProvider.client.postgrest["advertisements"]
                    .select()
                    .decodeList<AdMedia>()
            }

            val adsDir = AdManager.getAdsDir(applicationContext)
            val localFiles = adsDir.listFiles()?.toList() ?: emptyList()

            // 2. Download missing/changed files, using md5_hash to detect
            // content changes on files that already exist locally (previously
            // this only checked existence, so a changed asset with the same
            // id/filename would never be re-downloaded).
            remoteAds.forEach { ad ->
                // TEXT (announcement) and TEXT_AD (promotional copy) both
                // carry their content inline via text_content -- no media
                // file to fetch, just the DB row synced via
                // AdManager.savePlaylist below.
                if (ad.media_type == "TEXT" || ad.media_type == "TEXT_AD") return@forEach
                val mediaUrl = ad.media_url ?: return@forEach

                val fileName = ad.id + getExtension(mediaUrl)
                val targetFile = File(adsDir, fileName)

                val needsDownload = !targetFile.exists() || !md5Matches(targetFile, ad.md5_hash)
                if (needsDownload) {
                    downloadFile(mediaUrl, targetFile)

                    // Verify integrity post-download; a corrupted/truncated
                    // download must not be left in place for the player to choke on.
                    if (!ad.md5_hash.isNullOrBlank() && !md5Matches(targetFile, ad.md5_hash)) {
                        Log.e("AdSyncWorker", "MD5 mismatch after download for ${ad.id}, discarding")
                        targetFile.delete()
                    }
                }
            }

            // 3. Cleanup old files
            val remoteIds = remoteAds.map { it.id }
            localFiles.forEach { file ->
                val idInFile = file.name.substringBefore(".")
                if (idInFile !in remoteIds) {
                    file.delete()
                    Log.i("AdSyncWorker", "Deleted old ad: ${file.name}")
                }
            }

            // 4. Update local playlist JSON
            AdManager.savePlaylist(applicationContext, remoteAds)

            // Ensure all ad files are readable by external MediaPlayer process
            adsDir.listFiles()?.forEach { file ->
                try {
                    file.setReadable(true, false)
                } catch (e: Exception) {
                    Log.w("AdSyncWorker", "Failed to set readable: ${file.name}")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("AdSyncWorker", "Sync failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun downloadFile(url: String, file: File) {
        try {
            Log.i("AdSyncWorker", "Downloading: $url")
            val response: HttpResponse = client.get(url)
            val channel: ByteReadChannel = response.bodyAsChannel()
            
            FileOutputStream(file).use { output ->
                channel.copyTo(output)
            }
            Log.i("AdSyncWorker", "Download complete: ${file.name}")
        } catch (e: Exception) {
            Log.e("AdSyncWorker", "Download failed for $url: ${e.message}")
            throw e
        }
    }

    private fun md5Matches(file: File, expectedMd5: String?): Boolean {
        if (expectedMd5.isNullOrBlank()) return true // no hash to compare against, assume unchanged
        return try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            val localMd5 = digest.digest().joinToString("") { "%02x".format(it) }
            localMd5.equals(expectedMd5, ignoreCase = true)
        } catch (e: Exception) {
            Log.w("AdSyncWorker", "MD5 check failed for ${file.name}: ${e.message}")
            false
        }
    }

    private fun getExtension(url: String): String {
        return when {
            url.contains(".mp4", ignoreCase = true) -> ".mp4"
            url.contains(".jpg", ignoreCase = true) -> ".jpg"
            url.contains(".png", ignoreCase = true) -> ".png"
            else -> ".bin"
        }
    }
}
