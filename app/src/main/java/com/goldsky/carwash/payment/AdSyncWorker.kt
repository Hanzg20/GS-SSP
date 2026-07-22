package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goldsky.carwash.model.AdMedia
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

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i("AdSyncWorker", "Starting Ad Sync...")
            
            // 1. Fetch remote playlist
            val remoteAds: List<AdMedia> = client.get("${SupabaseConfig.URL}/rest/v1/advertisements") {
                header("apikey", SupabaseConfig.KEY)
                header("Authorization", "Bearer ${SupabaseConfig.KEY}")
            }.body()

            val adsDir = AdManager.getAdsDir(applicationContext)
            val localFiles = adsDir.listFiles()?.toList() ?: emptyList()

            // 2. Download missing/changed files, using md5_hash to detect
            // content changes on files that already exist locally (previously
            // this only checked existence, so a changed asset with the same
            // id/filename would never be re-downloaded).
            remoteAds.forEach { ad ->
                val fileName = ad.id + getExtension(ad.media_url)
                val targetFile = File(adsDir, fileName)

                val needsDownload = !targetFile.exists() || !md5Matches(targetFile, ad.md5_hash)
                if (needsDownload) {
                    downloadFile(ad.media_url, targetFile)

                    // Verify integrity post-download; a corrupted/truncated
                    // download must not be left in place for the player to choke on.
                    if (ad.md5_hash.isNotBlank() && !md5Matches(targetFile, ad.md5_hash)) {
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

    private fun md5Matches(file: File, expectedMd5: String): Boolean {
        if (expectedMd5.isBlank()) return true // no hash to compare against, assume unchanged
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
