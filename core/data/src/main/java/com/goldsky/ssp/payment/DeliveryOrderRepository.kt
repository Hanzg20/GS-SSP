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
    private const val PREFS = "delivery_orders"
    private const val KEY_COLLECTED = "collected_pending"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Orders this terminal took money for whose PAID mark the cloud hasn't
     * confirmed yet (offline, or the write failed and sits in the queue).
     * Hidden from the list and refused for collection until the cloud stops
     * listing them as OPEN -- otherwise the order reappears on the next
     * reload and gets collected twice (D1001, 2026-10-10).
     */
    private fun collected(): Set<String> =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getStringSet(KEY_COLLECTED, emptySet()) ?: emptySet()

    private fun saveCollected(ids: Set<String>) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putStringSet(KEY_COLLECTED, ids)?.commit()
    }

    /** Call the moment money is taken, before any network write. */
    fun markCollectedLocally(orderId: String) {
        saveCollected(collected() + orderId)
    }

    fun isCollectedLocally(orderId: String): Boolean = orderId in collected()

    /** [cloudOpen] = the OPEN list just fetched; anything collected here that's no longer in it is settled. */
    internal fun visibleOpen(cloudOpen: List<DeliveryOrder>, collectedIds: Set<String>): Pair<List<DeliveryOrder>, Set<String>> {
        val openIds = cloudOpen.map { it.id }.toSet()
        return cloudOpen.filter { it.id !in collectedIds } to collectedIds.filter { it in openIds }.toSet()
    }

    @Serializable
    private data class MarkPaidParams(val p_order_id: String, val p_ecr_ref_num: String, val p_payment_method: String)

    @Serializable
    private data class MarkPaidResult(val success: Boolean, val message: String? = null)

    @Serializable
    private data class StatusRow(val status: String)

    @Serializable
    data class DeliveryOrderItemInput(
        val name: String,
        val quantity: Int,
        val unitPriceCents: Int,
        val productId: String? = null,
        val barcode: String? = null,
        val taxRateBps: Int = 0
    )

    @Serializable
    data class CreateDeliveryOrderResult(
        val success: Boolean,
        val id: String? = null,
        val orderNo: Long? = null,
        val subtotalCents: Int = 0,
        val taxCents: Int = 0,
        val message: String? = null
    )

    @Serializable
    private data class RpcItem(
        val name: String,
        val quantity: Int,
        val unit_price_cents: Int,
        val product_id: String? = null,
        val barcode: String? = null,
        val tax_rate_bps: Int = 0
    )

    @Serializable
    private data class CreateOrderRpcParams(
        val p_customer_name: String,
        val p_customer_phone: String? = null,
        val p_address: String,
        val p_note: String? = null,
        val p_items: List<RpcItem>,
        val p_assigned_device_sn: String? = null
    )

    @Serializable
    private data class RpcCreateResult(
        val success: Boolean,
        val id: String? = null,
        val order_no: Long? = null,
        val subtotal_cents: Int? = null,
        val tax_cents: Int? = null,
        val message: String? = null
    )

    /** Open orders, oldest first, minus ones collected here; null when the cloud can't be reached. */
    suspend fun fetchOpen(): List<DeliveryOrder>? = withContext(Dispatchers.IO) {
        try {
            val cloud = SupabaseClientProvider.client.postgrest["delivery_orders"]
                .select(Columns.raw("id, order_no, customer_name, customer_phone, address, note, status, assigned_device_sn, delivery_order_items(*)")) {
                    filter { eq("status", "OPEN") }
                    order("order_no", Order.ASCENDING)
                }
                .decodeList<DeliveryOrder>()
            val (visible, stillPending) = visibleOpen(cloud, collected())
            saveCollected(stillPending)
            visible
        } catch (e: Exception) {
            Log.e(TAG, "Fetch open orders failed: ${e.message}")
            null
        }
    }

    /**
     * Creates a delivery order (小海狸's confirmation card) through the
     * create_delivery_order RPC, which places it in this device's own store and
     * computes the totals server side. Nothing is kept locally on failure: an
     * order that only lived on this terminal could be lost on restart while the
     * staff believed it had been sent -- the caller says "not saved" instead.
     */
    suspend fun createOrder(
        context: Context,
        customerName: String,
        customerPhone: String?,
        address: String,
        note: String?,
        items: List<DeliveryOrderItemInput>,
        assignedDeviceSn: String? = null
    ): CreateDeliveryOrderResult = withContext(Dispatchers.IO) {
        if (customerName.isBlank() || address.isBlank() || items.isEmpty()) {
            return@withContext CreateDeliveryOrderResult(false, message = "Customer, address and items required")
        }
        if (items.any { it.quantity <= 0 || it.unitPriceCents <= 0 || it.name.isBlank() }) {
            return@withContext CreateDeliveryOrderResult(false, message = "Every item needs a name, a quantity and a price")
        }
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val rpcParams = CreateOrderRpcParams(
                p_customer_name = customerName.trim(),
                p_customer_phone = customerPhone?.trim()?.ifBlank { null },
                p_address = address.trim(),
                p_note = note?.trim()?.ifBlank { null },
                p_items = items.map {
                    RpcItem(
                        name = it.name.trim(), quantity = it.quantity, unit_price_cents = it.unitPriceCents,
                        product_id = it.productId, barcode = it.barcode, tax_rate_bps = it.taxRateBps
                    )
                },
                p_assigned_device_sn = assignedDeviceSn
            )
            val res = SupabaseClientProvider.client.postgrest
                .rpc("create_delivery_order", rpcParams)
                .decodeAs<RpcCreateResult>()
            if (res.success && res.id != null) {
                CreateDeliveryOrderResult(
                    success = true, id = res.id, orderNo = res.order_no,
                    subtotalCents = res.subtotal_cents ?: 0, taxCents = res.tax_cents ?: 0
                )
            } else {
                Log.w(TAG, "create_delivery_order refused: ${res.message}")
                CreateDeliveryOrderResult(false, message = res.message ?: "Not saved")
            }
        } catch (e: Exception) {
            Log.e(TAG, "create_delivery_order failed: ${e.message}")
            CreateDeliveryOrderResult(false, message = "Not saved -- check the connection and try again")
        }
    }

    /**
     * Current status, checked right before charging so a card isn't charged
     * for an order another terminal already collected or the store cancelled.
     * null when unreachable.
     */
    suspend fun fetchStatus(orderId: String): String? = withContext(Dispatchers.IO) {
        if (isCollectedLocally(orderId)) return@withContext "COLLECTED"
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
            markCollectedLocally(orderId)
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
