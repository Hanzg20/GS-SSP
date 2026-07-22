package com.goldsky.carwash.payment

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class ErrorLog(
    val device_sn: String,
    val severity: String,
    val error_code: String,
    val stack_trace: String?,
    val context: JsonObject? = null
)

@Serializable
data class MaintenanceRecord(
    val device_sn: String,
    val action: String,
    val payload: JsonObject? = null
)

/**
 * Handles industrial-grade diagnostic logging and maintenance tracking.
 */
object DiagnosticManager {
    private const val TAG = "DiagnosticManager"

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /**
     * Reports a critical application error to the cloud. Returns the
     * underlying Job so time-sensitive callers (e.g. a crash handler about to
     * call System.exit) can join it with a timeout instead of firing-and-
     * forgetting a network call the process is about to kill mid-flight.
     */
    fun reportError(sn: String, code: String, severity: String = "ERROR", trace: String? = null): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            try {
                val log = ErrorLog(sn, severity, code, trace)
                client.post("${SupabaseConfig.URL}/rest/v1/app_error_logs") {
                    header("apikey", SupabaseConfig.KEY)
                    header("Authorization", "Bearer ${SupabaseConfig.KEY}")
                    header("Content-Type", "application/json")
                    setBody(log)
                }
                Log.i(TAG, "Error reported: $code")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report error: ${e.message}")
            }
        }
    }

    /**
     * Records a technician action (e.g. relay test).
     */
    fun recordMaintenance(sn: String, action: String, details: JsonObject? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val record = MaintenanceRecord(sn, action, details)
                client.post("${SupabaseConfig.URL}/rest/v1/maintenance_records") {
                    header("apikey", SupabaseConfig.KEY)
                    header("Authorization", "Bearer ${SupabaseConfig.KEY}")
                    header("Content-Type", "application/json")
                    setBody(record)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record maintenance: ${e.message}")
            }
        }
    }
}
