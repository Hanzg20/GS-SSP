package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.model.SaleLine
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class DeliveryOrderItem(
    val line_no: Int,
    val product_id: String? = null,
    val name: String,
    val barcode: String? = null,
    val quantity: Int,
    val unit_price_cents: Int,
    val tax_rate_bps: Int = 0
)

/** A delivery_orders row with its lines (docs/migrations/2026-10-10_delivery_orders.sql). */
@Serializable
data class DeliveryOrder(
    val id: String,
    val order_no: Long,
    val customer_name: String,
    val customer_phone: String? = null,
    val address: String,
    val note: String? = null,
    val status: String,
    val assigned_device_sn: String? = null,
    val delivery_order_items: List<DeliveryOrderItem> = emptyList()
) {
    val lines: List<SaleLine>
        get() = delivery_order_items.sortedBy { it.line_no }.map {
            SaleLine(it.name, it.quantity, it.unit_price_cents, it.tax_rate_bps, it.product_id, it.barcode)
        }
    val subtotalCents: Int get() = lines.sumOf { it.lineTotalCents }
    // Recomputed from the lines with the counter-sale rule, not CMP's stored copy.
    val taxCents: Int get() = SalesTax.cents(lines.map { it.lineTotalCents to it.taxRateBps })
}

/**
 * Delivery orders entered in CMP, collected at the door on Iris. RLS shows a
 * device its org's orders that are unassigned or assigned to it; marking one
 * paid goes through mark_delivery_order_paid (SECURITY DEFINER), never a raw UPDATE.
 */
object DeliveryOrderRepository {
    private const val TAG = "DeliveryOrders"

    @Serializable
    private data class MarkPaidParams(val p_order_id: String, val p_ecr_ref_num: String, val p_payment_method: String)

    @Serializable
    private data class MarkPaidResult(val success: Boolean, val message: String? = null)

    @Serializable
    private data class StatusRow(val status: String)

    /** Open orders, oldest first; null when the cloud can't be reached. */
    suspend fun fetchOpen(): List<DeliveryOrder>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.client.postgrest["delivery_orders"]
                .select(Columns.raw("id, order_no, customer_name, customer_phone, address, note, status, assigned_device_sn, delivery_order_items(*)")) {
                    filter { eq("status", "OPEN") }
                    order("order_no", Order.ASCENDING)
                }
                .decodeList<DeliveryOrder>()
        } catch (e: Exception) {
            Log.e(TAG, "Fetch open orders failed: ${e.message}")
            null
        }
    }

    /**
     * Current status, checked right before charging so a card isn't charged
     * for an order another terminal already collected or the store cancelled.
     * null when unreachable.
     */
    suspend fun fetchStatus(orderId: String): String? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.client.postgrest["delivery_orders"]
                .select(Columns.raw("status")) { filter { eq("id", orderId) } }
                .decodeSingleOrNull<StatusRow>()?.status ?: "MISSING"
        } catch (e: Exception) {
            Log.w(TAG, "Status check failed for $orderId: ${e.message}")
            null
        }
    }

    /**
     * Marks the order paid by [ecrRefNum]. Call after the sale's transaction
     * row was recorded (paid_ecr_ref_num references it); queued offline behind
     * it when the cloud can't be reached.
     */
    suspend fun markPaid(context: Context, orderId: String, ecrRefNum: String, paymentMethod: String): Boolean =
        withContext(Dispatchers.IO) {
            val ok = markPaidRemote(orderId, ecrRefNum, paymentMethod)
            if (ok == null) {
                OfflineQueueManager.enqueue(
                    context.filesDir,
                    PendingOp(type = "delivery_paid", orderId = orderId, ecrRefNum = ecrRefNum, paymentMethod = paymentMethod)
                )
                Log.w(TAG, "Mark paid queued offline: $orderId -> $ecrRefNum")
            }
            ok == true
        }

    /**
     * true = marked (or already marked by this same payment); false = refused
     * (already paid by another payment, cancelled, not this device's) -- final,
     * retrying won't change it; null = transport failure, worth retrying.
     */
    suspend fun markPaidRemote(orderId: String, ecrRefNum: String, paymentMethod: String): Boolean? =
        withContext(Dispatchers.IO) {
            try {
                val r = SupabaseClientProvider.client.postgrest
                    .rpc("mark_delivery_order_paid", MarkPaidParams(orderId, ecrRefNum, paymentMethod))
                    .decodeAs<MarkPaidResult>()
                if (!r.success) Log.e(TAG, "Mark paid refused for $orderId ($ecrRefNum): ${r.message}")
                else Log.i(TAG, "Order $orderId marked paid: $ecrRefNum (${r.message})")
                r.success
            } catch (e: Exception) {
                Log.e(TAG, "Mark paid failed for $orderId: ${e.message}")
                null
            }
        }
}
