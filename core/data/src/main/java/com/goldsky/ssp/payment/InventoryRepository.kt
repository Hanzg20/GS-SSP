package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Retail stock (docs/migrations/2026-11-01_inventory.sql): products.stock_qty, which
 * sales move by themselves (server triggers on PAID / refunded sale lines), and
 * retail.stock_moves through the retail_stock_* functions. Here: the store's list,
 * a product's history, and the changes staff make -- goods received, a count, an
 * adjustment with a reason, the low-stock level, stop tracking. Cloud only: a
 * change needs the server (null message = unreachable); nothing is queued, so the
 * number shown is always the server's.
 */
object InventoryRepository {
    private const val TAG = "Inventory"

    const val RECEIVE = "RECEIVE"
    const val COUNT = "COUNT"
    const val ADJUST = "ADJUST"
    const val UNTRACK = "UNTRACK"
    const val LOW = "LOW"

    @Serializable data class Item(
        val id: String,
        val name: String,
        val barcode: String? = null,
        val category: String? = null,
        val price_cents: Int = 0,
        /** null = not tracked. */
        val stock_qty: Int? = null,
        val low_stock_qty: Int = 5,
        /** Units sold (net of returns) in the last 7 days. */
        val sold_7d: Int = 0
    ) {
        val tracked: Boolean get() = stock_qty != null
        val out: Boolean get() = (stock_qty ?: 1) <= 0
        val low: Boolean get() = stock_qty != null && stock_qty <= low_stock_qty
    }

    @Serializable data class Move(
        val id: String,
        val name: String,
        val reason: String,
        val delta: Int,
        val qty_after: Int? = null,
        val ref: String? = null,
        val note: String? = null,
        val staff_name: String? = null,
        val device_sn: String? = null,
        val created_at: String
    )

    data class Result(val ok: Boolean, val message: String? = null, val stockQty: Int? = null)

    @Serializable private data class RpcResult(val success: Boolean = false, val message: String? = null, val stock_qty: Int? = null)
    @Serializable private data class MovesParams(val p_product_id: String?, val p_limit: Int)
    @Serializable private data class ChangeParams(
        val p_product_id: String, val p_kind: String, val p_ref: String,
        val p_qty: Int?, val p_note: String?, val p_staff_name: String?, val p_device_sn: String?
    )

    /** The store's retail products, tracked and low first; null offline. */
    suspend fun list(): List<Item>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest.rpc("retail_stock_list").decodeAs<List<Item>>()
        } catch (e: Exception) {
            Log.w(TAG, "Stock list failed: ${e.message}"); null
        }
    }

    /** Latest moves, of one product or the whole store; null offline. */
    suspend fun moves(productId: String?, limit: Int = 50): List<Move>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest.rpc("retail_stock_moves", MovesParams(productId, limit)).decodeAs<List<Move>>()
        } catch (e: Exception) {
            Log.w(TAG, "Stock moves failed: ${e.message}"); null
        }
    }

    /**
     * [kind]: RECEIVE (+qty, starts tracking), COUNT (set to qty), ADJUST (+/- qty,
     * [note] required), UNTRACK, LOW (qty = the low level, null = default). [ref] makes
     * a double tap one change: make it once per dialog.
     */
    suspend fun change(productId: String, kind: String, qty: Int?, note: String?, staff: StaffMember?, ref: String): Result =
        withContext(Dispatchers.IO) {
            try {
                SupabaseClientProvider.ensureAuthenticated()
                val r = SupabaseClientProvider.client.postgrest.rpc("retail_stock_change",
                    ChangeParams(productId, kind, ref, qty, note?.trim()?.ifBlank { null }, staff?.name, DeviceRepository.getPersistedDeviceSn())
                ).decodeAs<RpcResult>()
                if (!r.success) Log.w(TAG, "Stock $kind refused: ${r.message}")
                Result(r.success, r.message, r.stock_qty)
            } catch (e: Exception) {
                Log.e(TAG, "Stock $kind failed: ${e.message}")
                Result(false, null)
            }
        }

    fun newRef(): String = "STK-" + (DeviceRepository.getPersistedDeviceSn() ?: "X") + "-" + System.currentTimeMillis()

    /** Search by name or barcode (a scanner typing into the field, or part of the name). */
    fun filter(items: List<Item>, query: String, onlyLow: Boolean): List<Item> {
        val q = query.trim()
        return items.filter { i ->
            (!onlyLow || i.low) &&
                (q.isEmpty() || i.name.contains(q, ignoreCase = true) || (i.barcode != null && com.goldsky.ssp.model.Barcodes.canonical(i.barcode) == com.goldsky.ssp.model.Barcodes.canonical(q)))
        }
    }
}
