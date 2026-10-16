package com.goldsky.ssp.payment

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A manager's to-dos (cloud `reminders`, docs/migrations/2026-10-10_expenses_reminders.sql).
 * The terminal that set one shows a notification when it is due (a WorkManager
 * job, rescheduled on every app start so a reboot doesn't lose it); 小海狸's
 * brief lists what is due today or overdue on every terminal of the store.
 */
object ReminderRepository {
    private const val TAG = "ReminderRepository"
    const val CHANNEL = "reminders"

    @Serializable
    private data class ReminderRow(
        val id: String,
        val org_id: String,
        val device_sn: String? = null,
        val staff_id: String? = null,
        val staff_name: String? = null,
        val text: String,
        val due_at: String,
        val done_at: String? = null
    )

    @Serializable
    private data class DonePatch(val done_at: String)

    /** One to-do as the Journal lists it. [doneAtMillis] null: still open. */
    data class Reminder(val id: String, val text: String, val dueAtMillis: Long, val doneAtMillis: Long?, val staffName: String?)

    /**
     * The store's to-dos for the Journal (every terminal's, like 小海狸's brief): all
     * open ones by due time, then those done in the last 7 days, latest first. Null
     * offline -- reminders live in the cloud only.
     */
    suspend fun list(): List<Reminder>? = withContext(Dispatchers.IO) {
        val orgId = DeviceRepository.getPersistedOrgId() ?: return@withContext null
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val table = SupabaseClientProvider.client.postgrest["reminders"]
            val open = table.select {
                filter { eq("org_id", orgId); exact("done_at", null) }
                order("due_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                limit(200)
            }.decodeList<ReminderRow>()
            val done = table.select {
                filter { eq("org_id", orgId); gt("done_at", iso(System.currentTimeMillis() - 7 * 24 * 3_600_000L)) }
                order("done_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                limit(50)
            }.decodeList<ReminderRow>()
            (open + done).mapNotNull { r ->
                val due = DayReportRepository.parseIsoMillis(r.due_at) ?: return@mapNotNull null
                Reminder(r.id, r.text, due, r.done_at?.let { DayReportRepository.parseIsoMillis(it) }, r.staff_name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reminders not listed: ${e.message}")
            null
        }
    }

    /** Saves the reminder and schedules its notification here. False if it couldn't be saved (offline). */
    suspend fun create(context: Context, text: String, dueAtMillis: Long, staff: StaffMember?): Boolean = withContext(Dispatchers.IO) {
        val orgId = DeviceRepository.getPersistedOrgId() ?: return@withContext false
        val id = UUID.randomUUID().toString()
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest["reminders"].insert(
                ReminderRow(
                    id = id, org_id = orgId, device_sn = DeviceRepository.getPersistedDeviceSn(),
                    staff_id = staff?.id, staff_name = staff?.name, text = text.trim(), due_at = iso(dueAtMillis)
                )
            )
            schedule(context, id, text.trim(), dueAtMillis)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Reminder not saved: ${e.message}")
            false
        }
    }

    /** [context]: also drops this terminal's pending notification -- done early, nothing to remind. */
    suspend fun markDone(id: String, context: Context? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest["reminders"].update(DonePatch(iso(System.currentTimeMillis()))) {
                filter { eq("id", id) }
            }
            context?.let { WorkManager.getInstance(it).cancelUniqueWork("reminder_$id") }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Reminder $id not marked done: ${e.message}")
            false
        }
    }

    /** At app start: (re)schedule this terminal's open reminders -- WorkManager keeps them across a reboot, this covers the rest. */
    suspend fun rescheduleOpen(context: Context) = withContext(Dispatchers.IO) {
        val sn = DeviceRepository.getPersistedDeviceSn() ?: return@withContext
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val rows = SupabaseClientProvider.client.postgrest["reminders"].select {
                filter { eq("device_sn", sn); exact("done_at", null) }
            }.decodeList<ReminderRow>()
            rows.forEach { r ->
                val at = DayReportRepository.parseIsoMillis(r.due_at) ?: return@forEach
                // Future ones only: an overdue reminder already notified (or was missed while
                // the terminal was off) and is listed in 小海狸's brief -- re-posting it on every
                // app start notified the same reminder again and again.
                if (at > System.currentTimeMillis()) schedule(context, r.id, r.text, at)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reminders not rescheduled: ${e.message}")
        }
    }

    private fun schedule(context: Context, id: String, text: String, dueAtMillis: Long) {
        val delay = (dueAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reminder_$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("id" to id, "text" to text))
                .build()
        )
    }

    private fun iso(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(millis))

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders / 待办提醒", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }
}

/** Posts one reminder's notification; tapping it opens the app. */
class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val text = inputData.getString("text") ?: return Result.success()
        ReminderRepository.ensureChannel(applicationContext)
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = open?.let {
            PendingIntent.getActivity(applicationContext, id.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(applicationContext, ReminderRepository.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("小海狸提醒 · Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(id.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w("ReminderWorker", "Notifications not allowed: ${e.message}")
        }
        return Result.success()
    }
}
