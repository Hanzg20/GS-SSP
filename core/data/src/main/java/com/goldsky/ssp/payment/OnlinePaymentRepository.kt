package com.goldsky.ssp.payment

import android.util.Log
import com.goldsky.ssp.db.OrderEntity
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.call.body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Ourea Scan online payments that belong to this terminal (the store's Ourea):
 * they live in dine.payments only, never in transactions or the local orders
 * table, so the Ourea reads them from the cloud -- for its records list and to
 * refund one (supabase/functions/scan-pay-refund). No cloud, no online payments.
 */
object OnlinePaymentRepository {
    private const val TAG = "OnlinePayments"
    const val METHOD = "ONLINE"

    @Serializable private data class RangeParams(val p_start: String, val p_end: String)
    @Serializable private data class RefundRequest(val ecr_ref_num: String, val approver_id: String?, val approver_name: String)
    @Serializable private data class RefundResponse(val status: String? = null, val error: String? = null, val reason: String? = null)

    /** Outcome of a refund: [status] VOIDED / REFUNDED, else [error] (server code, or "offline"). */
    data class RefundResult(val status: String?, val error: String?, val reason: String? = null)

    /** This terminal's online payments of the last [days] days, as records-list rows; null offline. */
    suspend fun recent(days: Int = 30): List<OrderEntity>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val now = System.currentTimeMillis()
            val rows = SupabaseClientProvider.client.postgrest.rpc(
                "dine_online_sales",
                RangeParams(DayReportRepository.isoUtc(now - days * 86_400_000L), DayReportRepository.isoUtc(now + 86_400_000L))
            ).decodeAs<List<ReportTxn>>()
            rows.map { r ->
                OrderEntity(
                    ecrRefNum = r.ecr_ref_num,
                    subtotalCents = r.subtotal_cents ?: 0,
                    taxCents = r.tax_cents ?: 0,
                    tipCents = r.tip_cents ?: 0,
                    amountCents = r.amount,
                    status = r.payment_status ?: "PAID",
                    createdAt = DayReportRepository.parseIsoMillis(r.created_at) ?: 0L,
                    paymentMethod = METHOD
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Online payments unavailable: ${e.message}"); null
        }
    }

    /** Gives a whole online payment back (void, or refund once settled), approved by [approverName]. */
    suspend fun refund(ecrRefNum: String, approverId: String?, approverName: String): RefundResult = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val r = SupabaseClientProvider.invokeFunction("scan-pay-refund", RefundRequest(ecrRefNum, approverId, approverName))
                .body<RefundResponse>()
            if (r.status != null) Log.i(TAG, "Refunded $ecrRefNum: ${r.status} (approved by $approverName)")
            else Log.w(TAG, "Refund of $ecrRefNum refused: ${r.error} ${r.reason ?: ""}")
            RefundResult(r.status, r.error ?: if (r.status == null) "failed" else null, r.reason)
        } catch (e: Exception) {
            Log.e(TAG, "Refund of $ecrRefNum failed: ${e.message}")
            RefundResult(null, "offline")
        }
    }
}
