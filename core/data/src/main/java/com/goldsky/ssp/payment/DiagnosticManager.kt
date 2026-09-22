package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.*
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

    /**
     * Captures and uploads recent logcat entries to Supabase Storage.
     */
    suspend fun uploadLogs(sn: String, lineCount: Int = 2000): LogUploadResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Capturing $lineCount lines of logcat for $sn...")
            val process = Runtime.getRuntime().exec("logcat -d -t $lineCount")
            val logText = process.inputStream.bufferedReader().use { it.readText() }

            // On Android 12+ (API 31+, this app's targetSdk), a non-system app
            // calling into logcat like this triggers the OS's own "Access to
            // Logs" consent dialog (LogAccessDialogActivity) -- confirmed live
            // on a real Q3mini, 2026-09-23: the dialog opened and auto-dismissed
            // with nobody there to tap Allow. There is no app-level API to
            // suppress or pre-grant this; it needs a human physically at the
            // unit, which an unattended kiosk's remote-diagnostics use case
            // usually doesn't have. Silently reporting "Logs Uploaded" for
            // effectively-empty content (the actual observed failure mode)
            // would be worse than a clear distinct outcome here -- a technician
            // pulling this expecting real diagnostics needs to know the upload
            // itself succeeded but carried nothing useful, not just "it worked".
            val lines = logText.lineSequence().filter { it.isNotBlank() }.count()
            if (lines < MIN_USEFUL_LOG_LINES) {
                Log.w(TAG, "Logcat capture suspiciously small ($lines lines) -- likely blocked by the Android 12+ log-access consent dialog, not a real empty log")
                return@withContext LogUploadResult.Empty(lines)
            }

            val fileName = "logs/${sn}_${System.currentTimeMillis()}.txt"
            SupabaseClientProvider.client.storage["device-logs"].upload(
                path = fileName,
                data = logText.toByteArray(),
                upsert = true
            )
            Log.i(TAG, "Logcat successfully uploaded: $fileName ($lines lines)")
            LogUploadResult.Success(fileName, lines)
        } catch (e: Exception) {
            Log.e(TAG, "Logcat upload failed: ${e.message}")
            LogUploadResult.Failed(e.message ?: "unknown error")
        }
    }

    private const val MIN_USEFUL_LOG_LINES = 10
}

/**
 * Outcome of [DiagnosticManager.uploadLogs]. A plain Boolean previously
 * collapsed "genuinely uploaded something useful", "upload technically
 * succeeded but the captured text was suspiciously empty" (the real, observed
 * failure mode on Android 12+ -- see uploadLogs's doc comment), and "the
 * network/storage call itself failed" into the same two states, which is
 * exactly the kind of ambiguity that let the tech dashboard's EXPORT LOGS
 * button claim success for content nobody could actually use.
 */
sealed class LogUploadResult {
    data class Success(val fileName: String, val lineCount: Int) : LogUploadResult()
    data class Empty(val lineCount: Int) : LogUploadResult()
    data class Failed(val reason: String) : LogUploadResult()
}
