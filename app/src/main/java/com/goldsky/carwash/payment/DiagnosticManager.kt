package com.goldsky.carwash.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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
                SupabaseClientProvider.client.postgrest["app_error_logs"].insert(log)
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
                SupabaseClientProvider.client.postgrest["maintenance_records"].insert(record)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record maintenance: ${e.message}")
            }
        }
    }
}
