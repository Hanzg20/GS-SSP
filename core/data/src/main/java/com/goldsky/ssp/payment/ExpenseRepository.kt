package com.goldsky.ssp.payment

import com.goldsky.ssp.core.data.R
import android.content.Context
import android.util.Log
import com.goldsky.ssp.db.ExpenseEntity
import com.goldsky.ssp.db.LocalDatabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
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

    /** The category's name in the interface language (core/data res, values-<lang>). */
    fun label(context: Context, category: String): String = context.getString(
        when (category) {
            "INVENTORY" -> R.string.expense_cat_inventory
            "RENT" -> R.string.expense_cat_rent
            "UTILITIES" -> R.string.expense_cat_utilities
            "PAYROLL" -> R.string.expense_cat_payroll
            "REPAIRS" -> R.string.expense_cat_repairs
            "MARKETING" -> R.string.expense_cat_marketing
            "FEES" -> R.string.expense_cat_fees
            else -> R.string.expense_cat_other
        }
    )

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
        val created_at: String? = null,
        val tax_cents: Int? = null,
        val receipt_path: String? = null,
        val receipt_text: String? = null
    )

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * Saves locally, then uploads. Returns true once it is in the cloud (false = saved offline,
     * uploads later). [taxCents]: GST/HST paid, null = not recorded. [receiptFile]: the receipt
     * photo on this terminal (JPEG), uploaded with the expense -- the photo first, the row then
     * points at it; [receiptText]: what was read off it.
     */
    suspend fun record(
        context: Context, vendor: String, category: String, amountCents: Int, spentOn: String,
        note: String?, source: String, staff: StaffMember?,
        taxCents: Int? = null, receiptFile: String? = null, receiptText: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val entity = ExpenseEntity(
            vendor = vendor.trim(), amountCents = amountCents,
            category = if (category in CATEGORIES) category else "OTHER",
            spentOn = spentOn, note = note?.trim()?.ifBlank { null }, source = source, staffName = staff?.name,
            taxCents = taxCents, receiptFile = receiptFile, receiptText = receiptText?.take(20_000)
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
                    staffName = r.staff_name, synced = true,
                    taxCents = r.tax_cents, imageUrl = r.receipt_path, receiptText = r.receipt_text
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
            // The photo goes first: the row must never point at a photo that isn't there.
            val receiptPath = e.imageUrl ?: e.receiptFile?.let { uploadReceipt(context, orgId, e) }
            SupabaseClientProvider.client.postgrest["expenses"].upsert(
                ExpenseRow(
                    id = e.id, org_id = orgId, device_sn = DeviceRepository.getPersistedDeviceSn(),
                    staff_id = staffId, staff_name = e.staffName, vendor = e.vendor,
                    // Pre-cloud receipt scans were saved as "SUPPLY": purchases from suppliers.
                    category = when (e.category) { in CATEGORIES -> e.category; "SUPPLY" -> "INVENTORY"; else -> "OTHER" },
                    amount_cents = e.amountCents,
                    spent_on = e.spentOn ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(e.createdAt)),
                    note = e.note, source = e.source,
                    tax_cents = e.taxCents, receipt_path = receiptPath, receipt_text = e.receiptText
                ),
                onConflict = "id"
            )
            LocalDatabase.getInstance(context).expenseDao().markSynced(e.id)
            // In the cloud with its row: the copy here can go.
            if (receiptPath != null && e.receiptFile != null) {
                LocalDatabase.getInstance(context).expenseDao().receiptUploaded(e.id, receiptPath)
                runCatching { java.io.File(e.receiptFile).delete() }
            }
            true
        } catch (ex: Exception) {
            Log.w(TAG, "Expense ${e.id} not uploaded yet: ${ex.message}")
            false
        }
    }

    /**
     * Uploads the receipt photo to `receipts/<org>/<yyyy-MM>/<expense id>.jpg` and returns that path.
     * Throws when it can't (offline): the expense then waits for the next sync, photo and all. A
     * photo already there (an earlier attempt whose row didn't make it) counts as uploaded --
     * terminals can't overwrite a receipt.
     */
    private suspend fun uploadReceipt(context: Context, orgId: String, e: ExpenseEntity): String? {
        val file = java.io.File(e.receiptFile ?: return null)
        if (!file.exists()) {
            Log.w(TAG, "Receipt photo for ${e.id} is gone from this terminal; saving the expense without it")
            return null
        }
        val month = (e.spentOn ?: today()).take(7)
        val path = "$orgId/$month/${e.id}.jpg"
        try {
            SupabaseClientProvider.client.storage[RECEIPTS].upload(path, file.readBytes(), upsert = false)
        } catch (ex: Exception) {
            val msg = ex.message.orEmpty()
            if (!(msg.contains("Duplicate", ignoreCase = true) || msg.contains("already exists", ignoreCase = true) || msg.contains("409"))) throw ex
        }
        return path
    }

    private const val RECEIPTS = "receipts"

    /** A stored receipt photo (expenses.receipt_path); null when it can't be fetched (offline). */
    suspend fun receiptBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.storage[RECEIPTS].downloadAuthenticated(path)
        } catch (e: Exception) {
            Log.w(TAG, "Receipt $path not fetched: ${e.message}"); null
        }
    }

    /** Where a new receipt photo is kept on this terminal until its expense is uploaded. */
    fun newReceiptFile(context: Context): java.io.File =
        java.io.File(context.filesDir, "receipts").apply { mkdirs() }.resolve("${java.util.UUID.randomUUID()}.jpg")

    private fun parseIso(iso: String?): Long =
        iso?.let { runCatching { DayReportRepository.parseIsoMillis(it) }.getOrNull() } ?: System.currentTimeMillis()
}
