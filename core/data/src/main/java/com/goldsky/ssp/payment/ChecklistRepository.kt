package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Store checklists (opening, closing, any routine) for the Journal (手账): the
 * `office` schema, reached only through the public office_* functions
 * (docs/migrations/2026-10-16_office_checklists.sql, docs/database_design.md §5b).
 * Managers edit them; any signed-in staff member ticks today's steps. Cloud only:
 * every call returns null / a failed Result when the cloud can't be reached.
 */
object ChecklistRepository {
    private const val TAG = "ChecklistRepository"

    /** Every weekday: bit 0 = Monday ... bit 6 = Sunday (ISO), as office.checklists.days. */
    const val EVERY_DAY = 127

    @Serializable
    data class Step(
        val id: String? = null,
        val text: String,
        // Today's tick (office_today only): when, and by whom.
        val done_at: String? = null,
        val staff_name: String? = null
    ) {
        val done: Boolean get() = done_at != null
    }

    @Serializable
    data class Checklist(
        val id: String,
        val name: String,
        /** "HH:mm", store-local. */
        val due_time: String,
        val days: Int = EVERY_DAY,
        val items: List<Step> = emptyList()
    ) {
        val doneCount: Int get() = items.count { it.done }
        val complete: Boolean get() = items.isNotEmpty() && items.all { it.done }
    }

    @Serializable private data class TodayRpc(val business_day: String? = null, val checklists: List<Checklist> = emptyList())

    /** Outcome of a write: ok, or the server's reason ("name_taken", "bad_time"...), or null message = unreachable. */
    data class Result(val ok: Boolean, val message: String? = null, val id: String? = null)

    @Serializable private data class RpcResult(val success: Boolean = false, val message: String? = null, val id: String? = null)

    // RPC params: every argument always sent (a missing one = PostgREST can't find the function).
    @Serializable private data class SaveStep(val id: String?, val text: String)
    @Serializable private data class SaveParams(
        val p_id: String?, val p_name: String, val p_due_time: String, val p_days: Int,
        val p_items: List<SaveStep>, val p_active: Boolean, val p_staff_name: String?
    )
    @Serializable private data class TickParams(
        val p_item_id: String, val p_done: Boolean, val p_staff_id: String?, val p_staff_name: String?, val p_device_sn: String?
    )

    /** Today's checklists (by weekday) with today's ticks; null offline. */
    suspend fun today(): List<Checklist>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest.rpc("office_today").decodeAs<TodayRpc>().checklists
        } catch (e: Exception) {
            Log.w(TAG, "today failed: ${e.message}"); null
        }
    }

    /** Every checklist with its steps, for the managers' editor; null offline. */
    suspend fun all(): List<Checklist>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest.rpc("office_checklists").decodeAs<List<Checklist>>()
        } catch (e: Exception) {
            Log.w(TAG, "checklists failed: ${e.message}"); null
        }
    }

    /** Create ([id] null) or edit; [steps] in order, each with its id when it already exists. */
    suspend fun save(id: String?, name: String, dueTime: String, days: Int, steps: List<Step>, staff: StaffMember?): Result =
        call("office_save_checklist", SaveParams(id, name.trim(), dueTime.trim(), days,
            steps.filter { it.text.isNotBlank() }.map { SaveStep(it.id, it.text.trim()) }, true, staff?.name))

    /** Removes a checklist (its past ticks stay). */
    suspend fun remove(id: String): Result =
        call("office_save_checklist", SaveParams(id, "", "00:00", EVERY_DAY, emptyList(), false, null))

    suspend fun tick(stepId: String, done: Boolean, staff: StaffMember?): Result =
        call("office_tick", TickParams(stepId, done, staff?.id, staff?.name, DeviceRepository.getPersistedDeviceSn()))

    private suspend inline fun <reified P : Any> call(fn: String, params: P): Result = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val r = SupabaseClientProvider.client.postgrest.rpc(fn, params).decodeAs<RpcResult>()
            if (!r.success) Log.w(TAG, "$fn refused: ${r.message}")
            Result(r.success, r.message, r.id)
        } catch (e: Exception) {
            Log.e(TAG, "$fn failed: ${e.message}")
            Result(false, null)
        }
    }
}
