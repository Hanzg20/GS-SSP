package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goldsky.carwash.serial.SerialPortManager
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Heartbeat(
    val sn: String,
    val is_serial_ok: Boolean,
    val storage_free_mb: Long,
    val network_type: String
)

class HeartbeatWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val sn = getSn()
            val heartbeat = Heartbeat(
                sn = sn,
                is_serial_ok = SerialPortManager.isOpened(),
                storage_free_mb = getFreeSpace(),
                network_type = "WIFI" // Simplified
            )

            val token = DeviceRepository.getAuthToken()
            val response = client.post("${SupabaseConfig.URL}/rest/v1/heartbeats") {
                header("apikey", SupabaseConfig.KEY)
                header("Authorization", "Bearer ${token ?: SupabaseConfig.KEY}")
                header("Content-Type", "application/json")
                setBody(heartbeat)
            }

            if (response.status.isSuccess()) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e("HeartbeatWorker", "Failed to send heartbeat: ${e.message}")
            Result.retry()
        }
    }

    private fun getSn(): String {
        // In real app, reuse the SN extracted in MainActivity or call DAL again
        return "IM30_HARDWARE_SN" 
    }

    private fun getFreeSpace(): Long {
        return File(applicationContext.filesDir.absolutePath).freeSpace / (1024 * 1024)
    }
}
