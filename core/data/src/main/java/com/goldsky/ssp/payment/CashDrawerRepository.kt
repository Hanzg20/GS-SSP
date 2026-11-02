package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The cash drawer and its till sessions (docs/migrations/2026-11-02_cash_drawer.sql):
 * `retail.drawer_sessions` / `drawer_moves` through the retail_drawer_* functions,
 * one open session per terminal.
 *
 * Online only: opening, moving cash and closing are counted by the server, so
 * without it the terminal says so instead of keeping a second, local count. A
 * sale is never held up by the drawer.
 *
 * The drawer itself hangs off a network receipt printer (ESC/POS, port 9100): [kick]
 * sends the "open drawer" pulse. No printer set = nothing to open.
 */
object CashDrawerRepository {
    private const val TAG = "CashDrawer"
    private const val CONNECT_MS = 3000

    const val PAY_IN = "PAY_IN"
    const val PAY_OUT = "PAY_OUT"
    const val NO_SALE = "NO_SALE"

    @Serializable data class Move(
        val kind: String,
        val cents: Long = 0,
        val note: String? = null,
        val by: String? = null,
        val approved_by: String? = null,
        val at: String? = null
    )

    /** A session and its money; [open] = false and the rest empty when this terminal's drawer is closed. */
    @Serializable data class Session(
        val success: Boolean = true,
        val message: String? = null,
        val open: Boolean = false,
        val id: String? = null,
        val opened_at: String? = null,
        val opened_by: String? = null,
        val float_cents: Long = 0,
        val cash_sales: Int = 0,
        val cash_sales_cents: Long = 0,
        val cash_refunds: Int = 0,
        val cash_refunds_cents: Long = 0,
        val pay_in_cents: Long = 0,
        val pay_out_cents: Long = 0,
        val no_sale_opens: Int = 0,
        val moves: List<Move> = emptyList(),
        val expected_cents: Long = 0,
        val closed_at: String? = null,
        val closed_by: String? = null,
        val counted_cents: Long? = null,
        val over_short_cents: Long? = null,
        val close_note: String? = null
    )

    @Serializable private data class OpenParams(val p_float_cents: Long, val p_staff_name: String, val p_staff_id: String?)
    @Serializable private data class MoveParams(
        val p_kind: String, val p_cents: Long, val p_note: String?, val p_staff_name: String,
        val p_ref: String, val p_staff_id: String?, val p_approved_by: String?
    )
    @Serializable private data class CloseParams(val p_counted_cents: Long, val p_staff_name: String, val p_note: String?, val p_staff_id: String?)
    @Serializable private data class HistoryParams(val p_limit: Int)

    /** This terminal's drawer now; null = the server can't be reached. */
    suspend fun status(): Session? = call { SupabaseClientProvider.client.postgrest.rpc("retail_drawer_status").decodeAs<Session>() }

    suspend fun open(floatCents: Long, staff: StaffMember): Session? = call {
        SupabaseClientProvider.client.postgrest.rpc("retail_drawer_open", OpenParams(floatCents, staff.name, staff.id)).decodeAs<Session>()
    }

    /** Cash in / out (with a reason) or a no-sale open; [ref] makes a retry the same move. */
    suspend fun move(kind: String, cents: Long, note: String?, staff: StaffMember, ref: String, approvedBy: String? = null): Session? = call {
        SupabaseClientProvider.client.postgrest.rpc("retail_drawer_move", MoveParams(kind, cents, note, staff.name, ref, staff.id, approvedBy)).decodeAs<Session>()
    }

    suspend fun close(countedCents: Long, staff: StaffMember, note: String?): Session? = call {
        SupabaseClientProvider.client.postgrest.rpc("retail_drawer_close", CloseParams(countedCents, staff.name, note, staff.id)).decodeAs<Session>()
    }

    /** This terminal's last closed sessions, newest first. */
    suspend fun history(limit: Int = 10): List<Session>? = call {
        SupabaseClientProvider.client.postgrest.rpc("retail_drawer_history", HistoryParams(limit)).decodeAs<List<Session>>()
    }

    private suspend fun <T> call(block: suspend () -> T): T? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Drawer call failed: ${e.message}")
            null
        }
    }

    /** ESC p 0 25 250: pulse drawer pin 2 for 50 ms on, 500 ms off (the usual drawer cable). */
    val KICK = byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte())

    /** Opens the drawer on the printer set in Settings; false when none is set or it can't be reached. */
    suspend fun kick(): Boolean = withContext(Dispatchers.IO) {
        val host = DeviceRepository.getDrawerPrinterHost() ?: return@withContext false
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, DeviceRepository.getDrawerPrinterPort()), CONNECT_MS)
                s.soTimeout = CONNECT_MS
                s.getOutputStream().apply { write(KICK); flush() }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Drawer kick $host failed: ${e.message}")
            false
        }
    }
}
