package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goldsky.ssp.model.AdMedia
import com.goldsky.ssp.model.PlaylistEntry
import com.goldsky.ssp.model.TargetedAd
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
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

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i("AdSyncWorker", "Starting Ad Sync...")
            SupabaseClientProvider.ensureAuthenticated()

            val deviceSn = DeviceRepository.getPersistedDeviceSn()
            if (deviceSn == null) {
                Log.w("AdSyncWorker", "No device SN persisted yet, retrying later")
                return@withContext Result.retry()
            }

            DeviceRepository.syncDeviceIdentity(deviceSn)

            val entries: List<PlaylistEntry> = SupabaseClientProvider.client.postgrest["playlists"]
                .select {
                    filter { eq("device_sn", deviceSn) }
                    order("play_order", Order.ASCENDING)
                }
                .decodeList<PlaylistEntry>()

            val targetedPlaylist: List<TargetedAd> = if (entries.isNotEmpty()) {
                val adIds = entries.map { it.ad_id }
                val adsById = SupabaseClientProvider.client.postgrest["advertisements"]
                    .select {
                        filter { isIn("id", adIds) }
                    }
                    .decodeList<AdMedia>()
                    .associateBy { it.id }
                
                entries.mapNotNull { entry ->
                    adsById[entry.ad_id]?.let { media -> TargetedAd(media, entry) }
                }
            } else {
                Log.i("AdSyncWorker", "No specific playlist found for $deviceSn, falling back to all available ads")
                val globalAds = SupabaseClientProvider.client.postgrest["advertisements"]
                    .select()
                    .decodeList<AdMedia>()
                
                globalAds.map { media -> TargetedAd(media, PlaylistEntry(media.id)) }
            }

            val adsDir = AdManager.getAdsDir(applicationContext)
            val localFiles = adsDir.listFiles()?.toList() ?: emptyList()

            targetedPlaylist.forEach { targeted ->
                val ad = targeted.media
                if (ad.media_type == "TEXT" || ad.media_type == "TEXT_AD") return@forEach
                val mediaUrl = ad.media_url ?: return@forEach

                val fileName = ad.id + getExtension(mediaUrl)
                val targetFile = File(adsDir, fileName)

                val needsDownload = !targetFile.exists() || !md5Matches(targetFile, ad.md5_hash)
                if (needsDownload) {
                    downloadFile(mediaUrl, targetFile)

                    if (!ad.md5_hash.isNullOrBlank() && !md5Matches(targetFile, ad.md5_hash)) {
                        Log.e("AdSyncWorker", "MD5 mismatch after download for ${ad.id}, discarding")
                        targetFile.delete()
                    }
                }
            }

            val remoteIds = targetedPlaylist.map { it.media.id }
            localFiles.forEach { file ->
                val idInFile = file.name.substringBefore(".")
                if (idInFile !in remoteIds) {
                    file.delete()
                    Log.i("AdSyncWorker", "Deleted old ad: ${file.name}")
                }
            }

            AdManager.savePlaylist(applicationContext, targetedPlaylist)

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
        if (expectedMd5.isNullOrBlank()) return true 
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
