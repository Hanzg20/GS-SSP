package com.goldsky.carwash.payment

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PendingOp(
    val type: String, // "insert" | "update_hw"
    val record: TransactionRecord? = null,
    val ecrRefNum: String? = null,
    val status: String? = null
)

/**
 * File-backed queue for transaction writes that failed to reach Supabase
 * (offline / network error). Drained by TransactionReplayWorker once
 * connectivity returns, so a network drop mid-wash doesn't leave a paid
 * transaction permanently missing from the audit trail.
 *
 * Operates directly on a File (not Context) so it's plain-JVM unit testable;
 * see [queueFile] for the Android-side convenience wrapper.
 */
object OfflineQueueManager {
    private const val TAG = "OfflineQueueManager"
    const val QUEUE_FILE_NAME = "pending_transactions.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PendingOp.serializer())
    private val mutex = Mutex()

    fun queueFile(filesDir: File): File = File(filesDir, QUEUE_FILE_NAME)

    suspend fun enqueue(filesDir: File, op: PendingOp) = mutex.withLock {
        val current = readFile(queueFile(filesDir)).toMutableList()
        current.add(op)
        writeFile(queueFile(filesDir), current)
    }

    suspend fun size(filesDir: File): Int = mutex.withLock {
        readFile(queueFile(filesDir)).size
    }

    /**
     * Attempts to flush every queued op via [send]. Ops that fail again stay
     * queued, in original order, for the next drain attempt.
     */
    suspend fun drain(filesDir: File, send: suspend (PendingOp) -> Boolean) = mutex.withLock {
        val file = queueFile(filesDir)
        val pending = readFile(file)
        if (pending.isEmpty()) return@withLock

        val stillPending = mutableListOf<PendingOp>()
        for (op in pending) {
            val ok = try {
                send(op)
            } catch (e: Exception) {
                Log.w(TAG, "Replay failed for $op: ${e.message}")
                false
            }
            if (!ok) stillPending.add(op)
        }
        writeFile(file, stillPending)
        Log.i(
            TAG,
            "Replay drain: ${pending.size - stillPending.size}/${pending.size} succeeded, " +
                "${stillPending.size} remain queued"
        )
    }

    fun readFile(file: File): List<PendingOp> {
        return try {
            if (!file.exists()) emptyList()
            else json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read offline queue, resetting: ${e.message}")
            emptyList()
        }
    }

    fun writeFile(file: File, ops: List<PendingOp>) {
        try {
            file.writeText(json.encodeToString(serializer, ops))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write offline queue: ${e.message}")
        }
    }
}
