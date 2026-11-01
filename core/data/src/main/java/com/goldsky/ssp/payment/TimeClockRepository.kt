package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Staff time clock and the day's tip split (docs/migrations/2026-11-01_time_clock_tips.sql):
 * `office.shifts` / `office.settings` through the office_* functions.
 *
 * A punch is made on the terminal with its own time and id, kept in the local
 * "on the clock" list at once, and queued (OfflineQueueManager, "clock_in" /
 * "clock_out") when the cloud can't be reached -- it lands later at the time it
 * was made, once. [refresh] replaces the local list with the store's open shifts
 * (someone may have clocked in on another terminal), minus nothing: punches still
 * queued here are applied on top.
 */
object TimeClockRepository {
    private const val TAG = "TimeClock"
    private const val PREFS = "time_clock"
    private const val KEY_ON = "on_clock"
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), Long.serializer())

    const val OWN = "OWN"
    const val POOL_HOURS = "POOL_HOURS"
    const val POOL_EQUAL = "POOL_EQUAL"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** staff id -> clock-in time (epoch millis), as far as this terminal knows. */
    fun onClock(): Map<String, Long> =
        prefs()?.getString(KEY_ON, null)?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() } ?: emptyMap()

    private fun saveOnClock(m: Map<String, Long>) {
        prefs()?.edit()?.putString(KEY_ON, json.encodeToString(mapSerializer, m))?.apply()
    }

    fun clockedInAt(staffId: String): Long? = onClock()[staffId]

    @Serializable data class OpenShift(val id: String, val staff_id: String? = null, val name: String, val clock_in_at: String)

    @Serializable data class StaffDay(
        val staff_id: String? = null,
        val name: String,
        val minutes: Long = 0,
        val on_clock: Boolean = false,
        val own_tips_cents: Long = 0,
        val share_cents: Long = 0
    )

    @Serializable data class Shift(
        val id: String,
        val staff_id: String? = null,
        val name: String,
        val clock_in_at: String,
        val clock_out_at: String? = null,
        val fixed_by_name: String? = null,
        val fixed_note: String? = null
    )

    /** A business day's hours and tips for the whole store. */
    @Serializable data class DayTips(
        val day: String,
        val rule: String = OWN,
        val tips_cents: Long = 0,
        val unassigned_cents: Long = 0,
        val staff: List<StaffDay> = emptyList(),
        val shifts: List<Shift> = emptyList()
    )

    /** Outcome of a write: ok, or the server's reason; message null + !ok = unreachable. */
    data class Result(val ok: Boolean, val message: String? = null, val queued: Boolean = false)

    @Serializable private data class RpcResult(val success: Boolean = false, val message: String? = null, val id: String? = null)
    @Serializable private data class PunchParams(val p_staff_id: String, val p_ref: String, val p_at: String?, val p_device_sn: String?)
    @Serializable private data class DayParams(val p_day: String)
    @Serializable private data class RuleParams(val p_rule: String, val p_by_name: String?)
    @Serializable private data class FixParams(
        val p_id: String?, val p_clock_in_at: String, val p_note: String,
        val p_clock_out_at: String?, val p_staff_id: String?, val p_by_name: String?
    )

    fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    /**
     * Clock [staff] in ([clockIn] true) or out, now. On the local list at once; the
     * server's refusal ("already_in", "not_in") also corrects the list on the next
     * [refresh]. Queued when unreachable ([Result.queued]).
     */
    suspend fun punch(context: Context, staff: StaffMember, clockIn: Boolean): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val sn = DeviceRepository.getPersistedDeviceSn()
        val ref = (if (clockIn) "IN-" else "OUT-") + (sn ?: "X") + "-" + now
        val at = isoUtc(now)
        saveOnClock(if (clockIn) onClock() + (staff.id to now) else onClock() - staff.id)
        val r = punchRemote(clockIn, staff.id, ref, at)
        when {
            r == null -> {
                OfflineQueueManager.enqueue(context.filesDir,
                    PendingOp(type = if (clockIn) "clock_in" else "clock_out", staffId = staff.id, punchRef = ref, punchAt = at))
                Log.w(TAG, "Punch queued offline: ${staff.name} ${if (clockIn) "in" else "out"}")
                Result(true, queued = true)
            }
            r.success -> Result(true)
            else -> {
                // Already in (elsewhere) / not in: the cloud knows better -- take its list.
                refresh()
                Result(false, r.message)
            }
        }
    }

    /** null = unreachable (keep it queued); otherwise the server's answer, a refusal being final. */
    internal suspend fun punchRemote(clockIn: Boolean, staffId: String, ref: String, at: String?): RpcResultView? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val r = SupabaseClientProvider.client.postgrest
                .rpc(if (clockIn) "office_clock_in" else "office_clock_out", PunchParams(staffId, ref, at, DeviceRepository.getPersistedDeviceSn()))
                .decodeAs<RpcResult>()
            if (!r.success) Log.w(TAG, "Punch refused ($ref): ${r.message}")
            RpcResultView(r.success, r.message)
        } catch (e: Exception) {
            Log.e(TAG, "Punch failed ($ref): ${e.message}")
            null
        }
    }

    data class RpcResultView(val success: Boolean, val message: String?)

    /** The store's open shifts replace the local list; false when unreachable (the list stays). */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val open = SupabaseClientProvider.client.postgrest.rpc("office_on_clock").decodeAs<List<OpenShift>>()
            val ctx = appContext
            val queued = ctx?.let { OfflineQueueManager.readFile(OfflineQueueManager.queueFile(it.filesDir)) }.orEmpty()
            saveOnClock(merge(open, queued))
            true
        } catch (e: Exception) {
            Log.w(TAG, "On-clock refresh failed: ${e.message}")
            false
        }
    }

    /** The cloud's open shifts with this terminal's still-queued punches applied in order. */
    internal fun merge(open: List<OpenShift>, queued: List<PendingOp>): Map<String, Long> {
        val m = open.mapNotNull { s -> s.staff_id?.let { id -> parseIso(s.clock_in_at)?.let { id to it } } }.toMap().toMutableMap()
        queued.forEach { op ->
            val id = op.staffId ?: return@forEach
            when (op.type) {
                "clock_in" -> m[id] = op.punchAt?.let(::parseIso) ?: System.currentTimeMillis()
                "clock_out" -> m.remove(id)
            }
        }
        return m
    }

    /** A business day ("yyyy-MM-dd", America/Toronto): hours, tips and shares; null offline. */
    suspend fun day(day: String): DayTips? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest.rpc("office_tips", DayParams(day)).decodeAs<DayTips>()
        } catch (e: Exception) {
            Log.w(TAG, "Day tips failed: ${e.message}"); null
        }
    }

    suspend fun setRule(rule: String, by: StaffMember?): Result = call("office_set_tip_split", RuleParams(rule, by?.name))

    /**
     * A manager's fix: an existing shift ([shiftId]) gets these times, or a shift nobody
     * punched is added for [staffId] ([shiftId] null, both times). [clockOut] null leaves
     * it open. Reason required (the server refuses "note_required").
     */
    suspend fun fix(shiftId: String?, staffId: String?, clockIn: Long, clockOut: Long?, note: String, manager: StaffMember?): Result {
        val r = call("office_fix_shift", FixParams(shiftId, isoUtc(clockIn), note.trim(), clockOut?.let(::isoUtc), staffId, manager?.name))
        if (r.ok) refresh()
        return r
    }

    private suspend inline fun <reified P : Any> call(fn: String, params: P): Result = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val r = SupabaseClientProvider.client.postgrest.rpc(fn, params).decodeAs<RpcResult>()
            if (!r.success) Log.w(TAG, "$fn refused: ${r.message}")
            Result(r.success, r.message)
        } catch (e: Exception) {
            Log.e(TAG, "$fn failed: ${e.message}")
            Result(false, null)
        }
    }

    /** Epoch millis of a Postgres timestamptz; java.time needs API 26. */
    fun parseIso(iso: String?): Long? = try {
        iso?.let {
            val noFraction = it.replace(Regex("\\.\\d+"), "").replace("Z", "+00:00")
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(noFraction)?.time
        }
    } catch (e: Exception) { null }

    /** "3:05" for 185 minutes. */
    fun hours(minutes: Long): String = "%d:%02d".format(minutes / 60, minutes % 60)
}
