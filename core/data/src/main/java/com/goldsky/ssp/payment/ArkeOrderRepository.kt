package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.call.body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Arke online orders for this terminal's store (docs/migrations/2026-11-16_arke_online_orders.sql,
 * 2026-11-17_arke_delivery.sql): paid orders waiting to be accepted, being prepared, ready, and
 * the store's own deliveries out with a driver (already paid online -- nothing to collect at the
 * door), and Uber Eats orders (channel UBER_EATS, docs/migrations/2026-11-17_uber_eats_orders.sql:
 * shown and printed here, accepted / readied on Uber's tablet until ubereats-action exists); the store moves them on
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

    /** Where a delivery order goes (the customer's words; postal code normalised "K2K 1X1"). */
    @Serializable
    data class Address(
        val line1: String = "",
        val unit: String? = null,
        val city: String? = null,
        val postal: String? = null,
        val instructions: String? = null
    ) {
        val street: String get() = listOfNotNull(line1.takeIf { it.isNotBlank() }, unit?.takeIf { it.isNotBlank() }).joinToString(", ")
        val area: String get() = listOfNotNull(city?.takeIf { it.isNotBlank() }, postal?.takeIf { it.isNotBlank() }).joinToString(" ")
    }

    @Serializable
    data class Order(
        val id: String,
        val token: String? = null,
        val status: String,
        val fulfilment: String = "PICKUP",
        val channel: String = ARKE,
        val pickup_code: String? = null,
        val customer_name: String = "",
        val customer_phone: String? = null,
        val note: String? = null,
        val items: List<Line> = emptyList(),
        val subtotal_cents: Int = 0,
        val tax_cents: Int = 0,
        val tip_cents: Int = 0,
        val delivery_fee_cents: Int = 0,
        val delivery_address: Address? = null,
        val delivery_zone: String? = null,
        val total_cents: Int = 0,
        /** Arke ordering ahead: the slot the customer booked (pickup / delivery time); null = as soon as possible. */
        val scheduled_for: String? = null,
        val created_at: String? = null,
        val paid_at: String? = null,
        val accepted_at: String? = null,
        val ready_by: String? = null,
        /** A NEW order not accepted by then is cancelled and refunded on its own (gs-ssp 2026-11-19_arke_accept_deadline.sql). */
        val accept_by: String? = null,
        /** Rejected by Arke because nobody accepted it in time. */
        val auto_rejected: Boolean = false,
        val ready_at: String? = null,
        val picked_up_at: String? = null,
        val out_at: String? = null,
        val out_by: String? = null,
        val out_by_sn: String? = null,
        val delivered_at: String? = null,
        val delivery_attempts: Int = 0,
        val last_attempt_note: String? = null,
        val rejected_at: String? = null,
        val reject_reason: String? = null,
        val cancelled_at: String? = null,
        val cancel_reason: String? = null,
        val external_type: String? = null,
        val handled_by: String? = null
    ) {
        /** Still the store's to handle (not picked up / delivered / rejected). */
        val open: Boolean get() = status == NEW || status == ACCEPTED || status == READY || status == OUT_FOR_DELIVERY
        val isDelivery: Boolean get() = fulfilment == OWN_DELIVERY
        /** Uber Eats: Uber collected the money; moved on through ubereats-action (Uber first), never online_store_step. */
        val isUber: Boolean get() = channel == UBER_EATS
        val itemCount: Int get() = items.sumOf { it.quantity }
    }

    const val NEW = "NEW"
    const val ACCEPTED = "ACCEPTED"
    const val READY = "READY"
    const val PICKED_UP = "PICKED_UP"
    const val OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY"
    const val DELIVERED = "DELIVERED"
    const val REJECTED = "REJECTED"
    const val CANCELLED = "CANCELLED"
    const val ARKE = "ARKE"
    const val UBER_EATS = "UBER_EATS"
    const val OWN_DELIVERY = "OWN_DELIVERY"

    /** Outcome of a step: ok (+ the order as it is now), or the server's reason; message null = unreachable. */
    data class Result(val ok: Boolean, val message: String? = null, val order: Order? = null)

    @Serializable private data class StepParams(val p_id: String, val p_step: String, val p_minutes: Int?, val p_reason: String?,
                                                val p_staff_name: String?, val p_device_sn: String?)
    @Serializable private data class StepResult(val success: Boolean = false, val message: String? = null, val order: Order? = null)
    @Serializable private data class UberStep(val order_id: String, val step: String, val minutes: Int?, val reason: String?,
                                              val staff_name: String?, val device_sn: String?)
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

    /** [uber]: an Uber Eats order goes through ubereats-action -- Uber says yes first, then ours moves. */
    private suspend fun step(id: String, step: String, minutes: Int? = null, reason: String? = null, staffName: String?, uber: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            try {
                SupabaseClientProvider.ensureAuthenticated()
                val sn = DeviceRepository.getPersistedDeviceSn()
                val r = if (uber) SupabaseClientProvider.invokeFunction("ubereats-action", UberStep(id, step, minutes, reason, staffName, sn)).body<StepResult>()
                    else SupabaseClientProvider.client.postgrest.rpc("online_store_step", StepParams(id, step, minutes, reason, staffName, sn)).decodeAs<StepResult>()
                if (!r.success) Log.w(TAG, "$step $id refused: ${r.message}")
                Result(r.success, r.message, r.order)
            } catch (e: Exception) {
                Log.e(TAG, "$step $id failed: ${e.message}")
                Result(false, null)
            }
        }

    suspend fun accept(o: Order, minutes: Int, staffName: String?) = step(o.id, "ACCEPT", minutes = minutes, staffName = staffName, uber = o.isUber)
    suspend fun ready(o: Order, staffName: String?) = step(o.id, "READY", staffName = staffName, uber = o.isUber)
    suspend fun pickedUp(o: Order, staffName: String?) = step(o.id, "PICKED_UP", staffName = staffName, uber = o.isUber)
    suspend fun reject(o: Order, reason: String, staffName: String?) = step(o.id, "REJECT", reason = reason, staffName = staffName, uber = o.isUber)
    /** A driver takes a delivery out ([driver] = the staff name on this terminal). */
    suspend fun out(o: Order, driver: String?) = step(o.id, "OUT", staffName = driver, uber = o.isUber)
    suspend fun delivered(o: Order, staffName: String?) = step(o.id, "DELIVERED", staffName = staffName, uber = o.isUber)
    /** Couldn't hand it over: back in the store (READY), [reason] kept. */
    suspend fun back(o: Order, reason: String?, staffName: String?) = step(o.id, "BACK", reason = reason, staffName = staffName, uber = o.isUber)

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
