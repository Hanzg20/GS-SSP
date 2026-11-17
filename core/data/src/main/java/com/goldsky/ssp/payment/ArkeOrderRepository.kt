package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.call.body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Arke online orders for this terminal's store (docs/migrations/2026-11-16_arke_online_orders.sql):
 * paid orders waiting to be accepted, being prepared, ready; the store moves them on
 * (online_store_step) and gives a rejected one's money back (supabase/functions/arke-pay-refund).
 * The store comes from the signed-in device, never from here. No cloud = no online orders
 * (null), never a crash.
 */
object ArkeOrderRepository {
    private const val TAG = "ArkeOrders"

    @Serializable
    data class Line(
        val product_id: String? = null,
        val name: String,
        val options: List<String> = emptyList(),
        val quantity: Int,
        val unit_price_cents: Int = 0,
        val tax_rate_bps: Int = 0,
        val option_ids: List<String> = emptyList(),
        val note: String? = null
    )

    @Serializable
    data class Order(
        val id: String,
        val token: String? = null,
        val status: String,
        val fulfilment: String = "PICKUP",
        val pickup_code: String? = null,
        val customer_name: String = "",
        val customer_phone: String? = null,
        val note: String? = null,
        val items: List<Line> = emptyList(),
        val subtotal_cents: Int = 0,
        val tax_cents: Int = 0,
        val tip_cents: Int = 0,
        val total_cents: Int = 0,
        val created_at: String? = null,
        val paid_at: String? = null,
        val accepted_at: String? = null,
        val ready_by: String? = null,
        val ready_at: String? = null,
        val picked_up_at: String? = null,
        val rejected_at: String? = null,
        val reject_reason: String? = null,
        val handled_by: String? = null
    ) {
        /** Still the store's to handle (not picked up / rejected). */
        val open: Boolean get() = status == NEW || status == ACCEPTED || status == READY
        val itemCount: Int get() = items.sumOf { it.quantity }
    }

    const val NEW = "NEW"
    const val ACCEPTED = "ACCEPTED"
    const val READY = "READY"
    const val PICKED_UP = "PICKED_UP"
    const val REJECTED = "REJECTED"

    /** Outcome of a step: ok (+ the order as it is now), or the server's reason; message null = unreachable. */
    data class Result(val ok: Boolean, val message: String? = null, val order: Order? = null)

    @Serializable private data class StepParams(val p_id: String, val p_step: String, val p_minutes: Int?, val p_reason: String?,
                                                val p_staff_name: String?, val p_device_sn: String?)
    @Serializable private data class StepResult(val success: Boolean = false, val message: String? = null, val order: Order? = null)
    @Serializable private data class RefundRequest(val order_id: String)
    @Serializable private data class RefundResponse(val status: String? = null, val error: String? = null, val reason: String? = null)

    /** Today's paid orders still open, plus the last ones done (12 h); null when the cloud can't be reached. */
    suspend fun orders(): List<Order>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest.rpc("online_store_orders").decodeAs<List<Order>>()
        } catch (e: Exception) {
            Log.w(TAG, "orders failed: ${e.message}"); null
        }
    }

    private suspend fun step(id: String, step: String, minutes: Int? = null, reason: String? = null, staffName: String?): Result =
        withContext(Dispatchers.IO) {
            try {
                SupabaseClientProvider.ensureAuthenticated()
                val r = SupabaseClientProvider.client.postgrest.rpc(
                    "online_store_step", StepParams(id, step, minutes, reason, staffName, DeviceRepository.getPersistedDeviceSn())
                ).decodeAs<StepResult>()
                if (!r.success) Log.w(TAG, "$step $id refused: ${r.message}")
                Result(r.success, r.message, r.order)
            } catch (e: Exception) {
                Log.e(TAG, "$step $id failed: ${e.message}")
                Result(false, null)
            }
        }

    suspend fun accept(id: String, minutes: Int, staffName: String?) = step(id, "ACCEPT", minutes = minutes, staffName = staffName)
    suspend fun ready(id: String, staffName: String?) = step(id, "READY", staffName = staffName)
    suspend fun pickedUp(id: String, staffName: String?) = step(id, "PICKED_UP", staffName = staffName)
    suspend fun reject(id: String, reason: String, staffName: String?) = step(id, "REJECT", reason = reason, staffName = staffName)

    /** Gives a rejected order's payment back: VOIDED / REFUNDED, else the error ("offline" = unreachable). */
    suspend fun refund(orderId: String): OnlinePaymentRepository.RefundResult = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val r = SupabaseClientProvider.invokeFunction("arke-pay-refund", RefundRequest(orderId)).body<RefundResponse>()
            if (r.status != null) Log.i(TAG, "Refunded order $orderId: ${r.status}")
            else Log.w(TAG, "Refund of order $orderId refused: ${r.error} ${r.reason ?: ""}")
            OnlinePaymentRepository.RefundResult(r.status, r.error ?: if (r.status == null) "failed" else null, r.reason)
        } catch (e: Exception) {
            Log.e(TAG, "Refund of order $orderId failed: ${e.message}")
            OnlinePaymentRepository.RefundResult(null, "offline")
        }
    }
}
