package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.db.ExpenseEntity
import com.goldsky.ssp.db.LocalDatabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Store expenses: the cloud `expenses` table is the record
 * (docs/migrations/2026-10-10_expenses_reminders.sql), local_expenses its
 * offline copy. Saving writes locally first, then uploads; anything not yet
 * uploaded -- including expenses from before the cloud table existed -- goes
 * up on the next [list]/[syncPending]. Ids are made here, so a retried upload
 * can't book twice. Used by the Expenses page (by hand / receipt) and by
 * 小海狸's confirmation card (voice).
 */
object ExpenseRepository {
    private const val TAG = "ExpenseRepository"

    /** Same set as the table's CHECK; roughly what a Canadian small business reports for tax. */
    val CATEGORIES = listOf("INVENTORY", "RENT", "UTILITIES", "PAYROLL", "REPAIRS", "MARKETING", "FEES", "OTHER")

    fun label(category: String, zh: Boolean): String = when (category) {
        "INVENTORY" -> if (zh) "进货" else "Inventory"
        "RENT" -> if (zh) "房租" else "Rent"
        "UTILITIES" -> if (zh) "水电网" else "Utilities"
        "PAYROLL" -> if (zh) "工资" else "Payroll"
        "REPAIRS" -> if (zh) "维修" else "Repairs"
        "MARKETING" -> if (zh) "营销" else "Marketing"
        "FEES" -> if (zh) "手续费" else "Fees"
        else -> if (zh) "其他" else "Other"
    }

    @Serializable
    private data class ExpenseRow(
        val id: String,
        val org_id: String,
        val device_sn: String? = null,
        val staff_id: String? = null,
        val staff_name: String? = null,
        val vendor: String = "",
        val category: String = "OTHER",
        val amount_cents: Int,
        val spent_on: String,
        val note: String? = null,
        val source: String = "manual",
        val created_at: String? = null
    )

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Saves locally, then uploads. Returns true once it is in the cloud (false = saved offline, uploads later). */
    suspend fun record(
        context: Context, vendor: String, category: String, amountCents: Int, spentOn: String,
        note: String?, source: String, staff: StaffMember?
    ): Boolean = withContext(Dispatchers.IO) {
        val entity = ExpenseEntity(
            vendor = vendor.trim(), amountCents = amountCents,
            category = if (category in CATEGORIES) category else "OTHER",
            spentOn = spentOn, note = note?.trim()?.ifBlank { null }, source = source, staffName = staff?.name
        )
        LocalDatabase.getInstance(context).expenseDao().insert(entity)
        upload(context, entity, staff?.id)
    }

    /** Uploads every expense not in the cloud yet. */
    suspend fun syncPending(context: Context) = withContext(Dispatchers.IO) {
        val dao = LocalDatabase.getInstance(context).expenseDao()
        dao.unsynced().forEach { upload(context, it, null) }
    }

    /** The store's expenses, newest first: from the cloud when reachable, else the offline copy. */
    suspend fun list(context: Context, limit: Long = 300): List<ExpenseEntity> = withContext(Dispatchers.IO) {
        val dao = LocalDatabase.getInstance(context).expenseDao()
        syncPending(context)
        val orgId = DeviceRepository.getPersistedOrgId()
        if (orgId == null) return@withContext dao.getAll()
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val rows = SupabaseClientProvider.client.postgrest["expenses"].select {
                filter { eq("org_id", orgId) }
                order("spent_on", Order.DESCENDING)
                order("created_at", Order.DESCENDING)
                limit(limit)
            }.decodeList<ExpenseRow>()
            val cached = rows.map { r ->
                ExpenseEntity(
                    id = r.id, vendor = r.vendor, amountCents = r.amount_cents, category = r.category,
                    createdAt = parseIso(r.created_at), spentOn = r.spent_on, note = r.note, source = r.source,
                    staffName = r.staff_name, synced = true
                )
            }
            LocalDatabase.getInstance(context).runInTransaction {
                dao.deleteSynced()
                dao.insertAll(cached)
            }
            dao.getAll()
        } catch (e: Exception) {
            Log.w(TAG, "Expenses from the cloud failed, showing the offline copy: ${e.message}")
            dao.getAll()
        }
    }

    private suspend fun upload(context: Context, e: ExpenseEntity, staffId: String?): Boolean {
        val orgId = DeviceRepository.getPersistedOrgId() ?: return false
        return try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest["expenses"].upsert(
                ExpenseRow(
                    id = e.id, org_id = orgId, device_sn = DeviceRepository.getPersistedDeviceSn(),
                    staff_id = staffId, staff_name = e.staffName, vendor = e.vendor,
                    // Pre-cloud receipt scans were saved as "SUPPLY": purchases from suppliers.
                    category = when (e.category) { in CATEGORIES -> e.category; "SUPPLY" -> "INVENTORY"; else -> "OTHER" },
                    amount_cents = e.amountCents,
                    spent_on = e.spentOn ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(e.createdAt)),
                    note = e.note, source = e.source
                ),
                onConflict = "id"
            )
            LocalDatabase.getInstance(context).expenseDao().markSynced(e.id)
            true
        } catch (ex: Exception) {
            Log.w(TAG, "Expense ${e.id} not uploaded yet: ${ex.message}")
            false
        }
    }

    private fun parseIso(iso: String?): Long =
        iso?.let { runCatching { DayReportRepository.parseIsoMillis(it) }.getOrNull() } ?: System.currentTimeMillis()
}
