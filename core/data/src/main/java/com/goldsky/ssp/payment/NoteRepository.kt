package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.db.NoteEntity
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Store notes & memos: cloud `notes` table is the primary record,
 * `local_notes` in LocalDatabase is the offline copy.
 * Supports voice note taking from 小海狸 (Beaver) and manual management
 * inside Store Journal (手账).
 */
object NoteRepository {
    private const val TAG = "NoteRepository"

    @Serializable
    private data class NoteRow(
        val id: String,
        val org_id: String,
        val device_sn: String? = null,
        val staff_id: String? = null,
        val staff_name: String? = null,
        val title: String = "",
        val content: String,
        val tags: List<String> = emptyList(),
        val pinned: Boolean = false,
        val created_at: String? = null,
        val updated_at: String? = null
    )

    @Serializable
    private data class PinPatch(val pinned: Boolean, val updated_at: String)

    @Serializable
    private data class UpdatePatch(val title: String, val content: String, val pinned: Boolean, val updated_at: String)

    private val json = Json { ignoreUnknownKeys = true }

    /** Saves locally first, then uploads to Supabase. */
    suspend fun create(
        context: Context,
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        pinned: Boolean = false,
        staff: StaffMember? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanContent = content.trim()
        if (cleanContent.isBlank()) return@withContext false
        val cleanTitle = title.trim().ifBlank {
            cleanContent.lines().firstOrNull()?.take(30) ?: "便签"
        }
        val tagsJson = try { json.encodeToString(tags) } catch (_: Exception) { "[]" }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        val entity = NoteEntity(
            id = id,
            title = cleanTitle,
            content = cleanContent,
            tagsJson = tagsJson,
            pinned = pinned,
            staffName = staff?.name,
            createdAt = now,
            updatedAt = now,
            synced = false
        )
        LocalDatabase.getInstance(context).noteDao().insert(entity)
        upload(context, entity, tags, staff?.id)
    }

    private suspend fun upload(context: Context, note: NoteEntity, tags: List<String>, staffId: String?): Boolean {
        val orgId = DeviceRepository.getPersistedOrgId() ?: return false
        val sn = DeviceRepository.getPersistedDeviceSn()
        return try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest["notes"].insert(
                NoteRow(
                    id = note.id,
                    org_id = orgId,
                    device_sn = sn,
                    staff_id = staffId,
                    staff_name = note.staffName,
                    title = note.title,
                    content = note.content,
                    tags = tags,
                    pinned = note.pinned,
                    created_at = iso(note.createdAt),
                    updated_at = iso(note.updatedAt)
                )
            )
            LocalDatabase.getInstance(context).noteDao().insert(note.copy(synced = true))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Note upload deferred (offline?): ${e.message}")
            false
        }
    }

    /** Uploads unsynced notes */
    suspend fun syncPending(context: Context) = withContext(Dispatchers.IO) {
        val dao = LocalDatabase.getInstance(context).noteDao()
        val unsynced = dao.unsynced()
        for (item in unsynced) {
            val tags: List<String> = try { json.decodeFromString(item.tagsJson) } catch (_: Exception) { emptyList() }
            upload(context, item, tags, null)
        }
    }

    /** Fetches notes from Supabase when online, falling back to local Room DB */
    suspend fun list(context: Context, limit: Long = 100): List<NoteEntity> = withContext(Dispatchers.IO) {
        val dao = LocalDatabase.getInstance(context).noteDao()
        syncPending(context)
        val orgId = DeviceRepository.getPersistedOrgId()
        if (orgId == null) return@withContext dao.getAll()
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val rows = SupabaseClientProvider.client.postgrest["notes"].select {
                filter { eq("org_id", orgId) }
                order("pinned", Order.DESCENDING)
                order("created_at", Order.DESCENDING)
                limit(limit)
            }.decodeList<NoteRow>()

            val cached = rows.map { r ->
                val tagsJson = try { json.encodeToString(r.tags) } catch (_: Exception) { "[]" }
                NoteEntity(
                    id = r.id,
                    title = r.title,
                    content = r.content,
                    tagsJson = tagsJson,
                    pinned = r.pinned,
                    staffName = r.staff_name,
                    createdAt = parseIso(r.created_at),
                    updatedAt = parseIso(r.updated_at),
                    synced = true
                )
            }
            LocalDatabase.getInstance(context).runInTransaction {
                dao.deleteSynced()
                dao.insertAll(cached)
            }
            dao.getAll()
        } catch (e: Exception) {
            Log.w(TAG, "Listing cloud notes failed, using cached: ${e.message}")
            dao.getAll()
        }
    }

    suspend fun togglePin(context: Context, id: String, pinned: Boolean): Boolean = withContext(Dispatchers.IO) {
        val dao = LocalDatabase.getInstance(context).noteDao()
        val existing = dao.getAll().find { it.id == id }
        if (existing != null) {
            dao.insert(existing.copy(pinned = pinned, updatedAt = System.currentTimeMillis()))
        }
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest["notes"].update(PinPatch(pinned, iso(System.currentTimeMillis()))) {
                filter { eq("id", id) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "togglePin failed in cloud: ${e.message}")
            false
        }
    }

    suspend fun delete(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        LocalDatabase.getInstance(context).noteDao().deleteById(id)
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest["notes"].delete {
                filter { eq("id", id) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "delete failed in cloud: ${e.message}")
            false
        }
    }

    private fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date(millis))

    private fun parseIso(stamp: String?): Long {
        if (stamp == null) return System.currentTimeMillis()
        return DayReportRepository.parseIsoMillis(stamp) ?: System.currentTimeMillis()
    }
}
