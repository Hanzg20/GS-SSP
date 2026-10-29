package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** The transaction fields a day report needs. */
@Serializable
data class ReportTxn(
    val ecr_ref_num: String,
    val amount: Int,
    val subtotal_cents: Int? = null,
    val tax_cents: Int? = null,
    val tip_cents: Int? = null,
    val payment_status: String? = null,
    val payment_method: String? = null,
    val staff_name: String? = null,
    val created_at: String,
    val refunded_at: String? = null,
    val refund_approved_by_name: String? = null
)

@Serializable
data class ReportItemRow(val ecr_ref_num: String, val line_total_cents: Int, val tax_rate_bps: Int)

/**
 * End-of-day summary for one terminal and one day.
 *
 * Sales = sales taken that day that went through (PAID, or VOIDED/REFUNDED
 * later -- the money was collected). Refunds = refunds made that day, whatever
 * day the sale was. Net = sales - refunds; expected cash = cash sales - cash
 * refunds, the figure to count the drawer / a driver's cash against. A refund
 * counts against the staff member who took the original sale.
 */
data class DayReport(
    val sales: Totals,
    val byMethod: Map<String, Totals>,       // CASH / CREDIT_CARD / DEBIT_CARD / ...
    val byStaff: Map<String, StaffTotals>,   // staff name ("(none)" when not recorded)
    val taxCents: Int,
    val tipCents: Int,
    val taxableCents: Int,
    val exemptCents: Int,
    val refunds: List<Refund>,
    val refundCents: Int,
    val netCents: Int,
    val expectedCashCents: Int
) {
    data class Totals(val count: Int, val cents: Int)
    data class StaffTotals(val count: Int, val cents: Int, val cashCents: Int, val cardCents: Int, val refundCents: Int)
    data class Refund(val ref: String, val at: String, val cents: Int, val method: String, val status: String, val approvedBy: String?, val staff: String?)

    /**
     * REFUND_DATE: refunds made that day, whatever day the sale was -- for
     * counting cash. SALE_DATE: refunds (made so far) of that day's sales --
     * the day's net business.
     */
    enum class Basis { REFUND_DATE, SALE_DATE }

    companion object {
        private val COLLECTED = setOf("PAID", "VOIDED", "REFUNDED")
        private val REFUNDED = setOf("VOIDED", "REFUNDED")
        const val NO_STAFF = "(none)"

        fun isCard(method: String?) = method == "CREDIT_CARD" || method == "DEBIT_CARD"

        /**
         * [rows]: the terminal's transactions created in the day or refunded in
         * it; [inDay]: whether an ISO timestamp falls in the day; [items]: lines
         * of the day's sales (taxable vs. exempt split; sales without lines
         * count their subtotal as taxable when they carried tax, else exempt).
         */
        fun build(rows: List<ReportTxn>, items: List<ReportItemRow>, inDay: (String) -> Boolean, basis: Basis = Basis.REFUND_DATE): DayReport {
            val sales = rows.filter { inDay(it.created_at) && it.payment_status in COLLECTED }
            val refunds = when (basis) {
                Basis.REFUND_DATE -> rows.filter { it.payment_status in REFUNDED && it.refunded_at?.let(inDay) == true }
                Basis.SALE_DATE -> sales.filter { it.payment_status in REFUNDED }
            }

            val byMethod = sales.groupBy { it.payment_method ?: "UNKNOWN" }
                .mapValues { (_, l) -> Totals(l.size, l.sumOf { it.amount }) }

            val staffNames = (sales + refunds).map { it.staff_name ?: NO_STAFF }.distinct()
            val byStaff = staffNames.associateWith { name ->
                val mine = sales.filter { (it.staff_name ?: NO_STAFF) == name }
                StaffTotals(
                    count = mine.size,
                    cents = mine.sumOf { it.amount },
                    cashCents = mine.filter { it.payment_method == "CASH" }.sumOf { it.amount },
                    cardCents = mine.filter { isCard(it.payment_method) }.sumOf { it.amount },
                    refundCents = refunds.filter { (it.staff_name ?: NO_STAFF) == name }.sumOf { it.amount }
                )
            }

            val itemsByRef = items.groupBy { it.ecr_ref_num }
            var taxable = 0
            var exempt = 0
            for (s in sales) {
                val lines = itemsByRef[s.ecr_ref_num]
                if (lines.isNullOrEmpty()) {
                    val sub = s.subtotal_cents ?: (s.amount - (s.tax_cents ?: 0) - (s.tip_cents ?: 0))
                    if ((s.tax_cents ?: 0) > 0) taxable += sub else exempt += sub
                } else {
                    lines.forEach { if (it.tax_rate_bps > 0) taxable += it.line_total_cents else exempt += it.line_total_cents }
                }
            }

            val salesTotal = sales.sumOf { it.amount }
            val refundTotal = refunds.sumOf { it.amount }
            return DayReport(
                sales = Totals(sales.size, salesTotal),
                byMethod = byMethod,
                byStaff = byStaff,
                taxCents = sales.sumOf { it.tax_cents ?: 0 },
                tipCents = sales.sumOf { it.tip_cents ?: 0 },
                taxableCents = taxable,
                exemptCents = exempt,
                refunds = refunds.sortedBy { it.refunded_at }.map {
                    Refund(it.ecr_ref_num, it.refunded_at!!, it.amount, it.payment_method ?: "UNKNOWN",
                        it.payment_status!!, it.refund_approved_by_name, it.staff_name)
                },
                refundCents = refundTotal,
                netCents = salesTotal - refundTotal,
                expectedCashCents = (byMethod["CASH"]?.cents ?: 0) - refunds.filter { it.payment_method == "CASH" }.sumOf { it.amount }
            )
        }
    }
}

/**
 * The whole store's rough profit for a day (public.office_day_profit, the same
 * figures 小海狸 reads as profit_today): sales kept (PAID) before tax and tips,
 * minus that day's recorded expenses. Rough: stock is an expense the day it's paid.
 */
@Serializable
data class DayProfit(
    val sales: Int = 0,
    val sales_total_cents: Int = 0,
    val tax_cents: Int = 0,
    val tip_cents: Int = 0,
    val revenue_cents: Int = 0,
    val expenses_count: Int = 0,
    val expenses_cents: Int = 0,
    val expenses_by_category: Map<String, Int> = emptyMap(),
    val rough_profit_cents: Int = 0,
    val success: Boolean = true
)

object DayReportRepository {
    private const val TAG = "DayReport"

    @Serializable private data class ProfitParams(val p_day: String)
    @Serializable private data class RangeParams(val p_start: String, val p_end: String)

    /** [day] "yyyy-MM-dd" (store-local); null offline or when the server refuses. */
    suspend fun profit(day: String): DayProfit? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest.rpc("office_day_profit", ProfitParams(day)).decodeAs<DayProfit>().takeIf { it.success }
        } catch (e: Exception) {
            Log.w(TAG, "Day profit failed: ${e.message}"); null
        }
    }

    /** [local midnight, next local midnight) of the day containing [dayMillis], as UTC ISO strings + millis. */
    fun dayBounds(dayMillis: Long): Pair<Long, Long> {
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = dayMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = c.timeInMillis
        c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return start to c.timeInMillis
    }

    fun isoUtc(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(millis))

    /**
     * Postgres timestamptz as PostgREST returns it ("2026-10-10T13:13:38.403869+00:00",
     * fraction 0-6 digits). java.time needs API 26 (minSdk 25), so: fraction cut
     * to milliseconds, then SimpleDateFormat with an ISO offset (X, API 24+).
     */
    fun parseIsoMillis(ts: String): Long? {
        val m = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})$""").find(ts.trim()) ?: return null
        val (base, frac, zone) = m.destructured
        val ms = (frac + "000").take(3)
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).parse("$base.$ms${if (zone == "Z") "+00:00" else zone}")?.time
        }.getOrNull()
    }

    /**
     * This terminal's transactions created or refunded in [startIso, endIso),
     * plus the lines of the ones created in it. Null when the cloud can't be
     * reached (refund approvers etc. only live there).
     *
     * Includes the Ourea Scan online payments that belong to this terminal (the
     * store's Ourea): they are only in dine.payments, never in transactions
     * (database_design.md §4), so dine_online_sales hands them over as rows
     * of the same shape -- method ONLINE, staff "Ourea Scan".
     */
    suspend fun fetch(deviceSn: String, startIso: String, endIso: String): Pair<List<ReportTxn>, List<ReportItemRow>>? =
        withContext(Dispatchers.IO) {
            try {
                val cols = Columns.raw("ecr_ref_num, amount, subtotal_cents, tax_cents, tip_cents, payment_status, payment_method, staff_name, created_at, refunded_at, refund_approved_by_name")
                val created = SupabaseClientProvider.client.postgrest["transactions"].select(cols) {
                    filter { eq("device_sn", deviceSn); gte("created_at", startIso); lt("created_at", endIso) }
                }.decodeList<ReportTxn>()
                val refunded = SupabaseClientProvider.client.postgrest["transactions"].select(cols) {
                    filter { eq("device_sn", deviceSn); gte("refunded_at", startIso); lt("refunded_at", endIso) }
                }.decodeList<ReportTxn>()
                val online = SupabaseClientProvider.client.postgrest
                    .rpc("dine_online_sales", RangeParams(startIso, endIso)).decodeAs<List<ReportTxn>>()
                val rows = (created + refunded + online).distinctBy { it.ecr_ref_num }
                val refs = created.map { it.ecr_ref_num }
                val items = refs.chunked(100).flatMap { chunk ->
                    SupabaseClientProvider.client.postgrest["transaction_items"]
                        .select(Columns.raw("ecr_ref_num, line_total_cents, tax_rate_bps")) { filter { isIn("ecr_ref_num", chunk) } }
                        .decodeList<ReportItemRow>()
                }
                rows to items
            } catch (e: Exception) {
                Log.e(TAG, "Day report fetch failed: ${e.message}")
                null
            }
        }
}
