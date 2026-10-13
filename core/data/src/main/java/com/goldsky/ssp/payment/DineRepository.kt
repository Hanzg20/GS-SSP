package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Dine-in (table service): the floor, open checks, rounds sent to the kitchen
 * and payments on a check (docs/migrations/2026-10-13_dine_in.sql). Reads go
 * straight to the tables (RLS: the device's store); every write is an RPC
 * that takes the store from the calling device.
 *
 * The lines being taken for a round live in the device's cart until they're
 * sent: the cloud only ever holds what the kitchen has.
 */
object DineRepository {
    private const val TAG = "DineRepository"

    @Serializable
    data class DiningTable(
        val id: String,
        val name: String,
        val area: String? = null,
        val seats: Int = 4,
        val sort: Int = 0,
        val is_active: Boolean = true
    )

    @Serializable
    data class Check(
        val id: String,
        val table_id: String,
        val check_no: Int,
        val status: String,
        val guests: Int = 1,
        val server_id: String? = null,
        val server_name: String? = null,
        val note: String? = null,
        val opened_at: String? = null
    ) {
        val isOpen: Boolean get() = status == "OPEN" || status == "BILLED"
    }

    @Serializable
    data class Item(
        val id: String,
        val check_id: String,
        val round: Int,
        val product_id: String? = null,
        val name: String,
        val options: List<String> = emptyList(),
        val quantity: Int,
        val unit_price_cents: Int,
        val tax_rate_bps: Int = 0,
        val note: String? = null,
        val status: String = "SENT",
        val paid_ecr_ref_num: String? = null,
        val sent_by: String? = null,
        val sent_at: String? = null
    ) {
        val isVoid: Boolean get() = status == "VOID"
        val lineTotalCents: Int get() = unit_price_cents * quantity
        /** The name as it goes on the receipt / transaction_items: "Noodle (Mild, Egg)". */
        val displayName: String get() = if (options.isEmpty()) name else "$name (${options.joinToString(", ")})"
    }

    @Serializable
    data class Payment(
        val id: String,
        val check_id: String,
        val ecr_ref_num: String,
        val amount_cents: Int,
        val tax_cents: Int = 0,
        val tip_cents: Int = 0,
        val method: String? = null,
        val paid_by: String? = null
    )

    /** A check with everything on it, and its money. */
    data class CheckDetail(val check: Check, val table: DiningTable?, val items: List<Item>, val payments: List<Payment>) {
        val liveItems: List<Item> get() = items.filter { !it.isVoid }
        val subtotalCents: Int get() = liveItems.sumOf { it.lineTotalCents }
        val taxCents: Int get() = SalesTax.cents(liveItems.map { it.lineTotalCents to it.tax_rate_bps })
        val totalCents: Int get() = subtotalCents + taxCents
        val paidCents: Int get() = payments.sumOf { it.amount_cents }
        val paidTaxCents: Int get() = payments.sumOf { it.tax_cents }
        val balanceCents: Int get() = (totalCents - paidCents).coerceAtLeast(0)
        /** Lines nobody has paid for on their own yet (split by items). */
        val unpaidItems: List<Item> get() = liveItems.filter { it.paid_ecr_ref_num == null }
        val rounds: Int get() = items.maxOfOrNull { it.round } ?: 0
    }

    /** A table on the floor with its live check, if any, and that check's total. */
    data class FloorTable(val table: DiningTable, val check: Check?, val totalCents: Int)

    /** A line for the next round (from the device's cart). */
    data class NewLine(
        val productId: String?, val name: String, val options: List<String>, val quantity: Int,
        val unitPriceCents: Int, val taxRateBps: Int, val note: String? = null
    )

    // -------------------------------------------------------------- reads

    // Reads go through RPCs too: the tables live in the `dine` schema, which the
    // API doesn't expose (docs/migrations/2026-10-13_dine_schema.sql).
    @Serializable private data class FloorCheck(
        val id: String, val table_id: String, val check_no: Int, val status: String, val guests: Int = 1,
        val server_id: String? = null, val server_name: String? = null, val note: String? = null,
        val opened_at: String? = null, val total_cents: Int = 0
    ) {
        fun check() = Check(id, table_id, check_no, status, guests, server_id, server_name, note, opened_at)
    }
    @Serializable private data class FloorRpc(val tables: List<DiningTable> = emptyList(), val checks: List<FloorCheck> = emptyList())
    @Serializable private data class CheckRpc(
        val check: Check, val table: DiningTable? = null,
        val items: List<Item> = emptyList(), val payments: List<Payment> = emptyList()
    )
    @Serializable private data class CheckIdParams(val p_check_id: String)

    suspend fun tables(): List<DiningTable>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest.rpc("dine_list_tables").decodeAs<List<DiningTable>>()
        } catch (e: Exception) {
            Log.w(TAG, "tables failed: ${e.message}"); null
        }
    }

    /** Tables plus live checks and their totals; null when the cloud can't be reached. */
    suspend fun floor(): List<FloorTable>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val f = SupabaseClientProvider.client.postgrest.rpc("dine_floor").decodeAs<FloorRpc>()
            val byTable = f.checks.associateBy { it.table_id }
            f.tables.map { t -> byTable[t.id].let { c -> FloorTable(t, c?.check(), c?.total_cents ?: 0) } }
        } catch (e: Exception) {
            Log.w(TAG, "floor failed: ${e.message}"); null
        }
    }

    suspend fun check(checkId: String): CheckDetail? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val r = SupabaseClientProvider.client.postgrest.rpc("dine_get_check", CheckIdParams(checkId)).decodeAsOrNull<CheckRpc>()
                ?: return@withContext null
            CheckDetail(r.check, r.table, r.items, r.payments)
        } catch (e: Exception) {
            Log.w(TAG, "check $checkId failed: ${e.message}"); null
        }
    }

    // -------------------------------------------------------------- writes

    /** Outcome of a write: ok, or the server's reason ("table_in_use", "name_taken"...), or null message = unreachable. */
    data class Result(val ok: Boolean, val message: String? = null, val id: String? = null, val round: Int? = null)

    @Serializable private data class RpcResult(
        val success: Boolean = false, val message: String? = null, val id: String? = null,
        val round: Int? = null, val check_no: Int? = null, val balance_cents: Int? = null, val closed: Boolean? = null
    )

    // RPC params: every argument always sent (a missing one = PostgREST can't find the function).
    @Serializable private data class SaveTableParams(val p_id: String?, val p_name: String, val p_area: String?, val p_seats: Int, val p_sort: Int, val p_active: Boolean)
    @Serializable private data class OpenParams(val p_table_id: String, val p_guests: Int, val p_server_id: String?, val p_server_name: String?, val p_device_sn: String?)
    @Serializable private data class RpcLine(val product_id: String?, val name: String, val options: List<String>, val quantity: Int,
                                             val unit_price_cents: Int, val tax_rate_bps: Int, val note: String?)
    @Serializable private data class SendParams(val p_check_id: String, val p_items: List<RpcLine>, val p_sent_by: String?)
    @Serializable private data class VoidParams(val p_item_id: String, val p_voided_by: String?)
    @Serializable private data class UpdateParams(val p_check_id: String, val p_status: String?, val p_guests: Int?, val p_note: String?)
    @Serializable private data class PayParams(val p_check_id: String, val p_ecr_ref_num: String, val p_amount_cents: Int, val p_tax_cents: Int, val p_tip_cents: Int,
                                               val p_method: String?, val p_paid_by: String?, val p_item_ids: List<String>?)

    private suspend inline fun <reified P : Any> call(fn: String, params: P): Result = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val r = SupabaseClientProvider.client.postgrest.rpc(fn, params).decodeAs<RpcResult>()
            if (!r.success) Log.w(TAG, "$fn refused: ${r.message}")
            Result(r.success, r.message, r.id, r.round)
        } catch (e: Exception) {
            Log.e(TAG, "$fn failed: ${e.message}")
            Result(false, null)
        }
    }

    suspend fun saveTable(id: String?, name: String, area: String?, seats: Int, sort: Int, active: Boolean = true): Result =
        call("dine_save_table", SaveTableParams(id, name.trim(), area?.trim()?.ifBlank { null }, seats, sort, active))

    suspend fun openCheck(tableId: String, guests: Int, serverId: String?, serverName: String?): Result =
        call("dine_open_check", OpenParams(tableId, guests, serverId, serverName, DeviceRepository.getPersistedDeviceSn()))

    suspend fun sendItems(checkId: String, lines: List<NewLine>, sentBy: String?): Result =
        call("dine_send_items", SendParams(checkId, lines.map {
            RpcLine(it.productId, it.name, it.options, it.quantity, it.unitPriceCents, it.taxRateBps, it.note?.trim()?.ifBlank { null })
        }, sentBy))

    suspend fun voidItem(itemId: String, voidedBy: String?): Result = call("dine_void_item", VoidParams(itemId, voidedBy))

    suspend fun updateCheck(checkId: String, status: String? = null, guests: Int? = null, note: String? = null): Result =
        call("dine_update_check", UpdateParams(checkId, status, guests, note))

    /**
     * After the money is taken: [amountCents] is food + tax covered (no tip).
     * Queued offline behind the transaction when the cloud can't be reached --
     * the customer has paid, the check must end up knowing it.
     */
    suspend fun recordPayment(
        context: Context, checkId: String, ecrRefNum: String, amountCents: Int, taxCents: Int, tipCents: Int,
        method: String?, paidBy: String?, itemIds: List<String>?
    ): Boolean = withContext(Dispatchers.IO) {
        val ok = recordPaymentRemote(checkId, ecrRefNum, amountCents, taxCents, tipCents, method, paidBy, itemIds)
        if (ok == null) {
            OfflineQueueManager.enqueue(
                context.filesDir,
                PendingOp(type = "dine_payment", orderId = checkId, ecrRefNum = ecrRefNum, paymentMethod = method,
                    amountCents = amountCents, taxCents = taxCents, tipCents = tipCents, itemIds = itemIds, paidBy = paidBy)
            )
            Log.w(TAG, "Check payment queued offline: $checkId <- $ecrRefNum")
        }
        ok == true
    }

    /** true = recorded; false = refused (final); null = unreachable, worth retrying. */
    suspend fun recordPaymentRemote(
        checkId: String, ecrRefNum: String, amountCents: Int, taxCents: Int, tipCents: Int,
        method: String?, paidBy: String?, itemIds: List<String>?
    ): Boolean? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val r = SupabaseClientProvider.client.postgrest
                .rpc("dine_record_payment", PayParams(checkId, ecrRefNum, amountCents, taxCents, tipCents, method, paidBy, itemIds))
                .decodeAs<RpcResult>()
            if (!r.success) Log.e(TAG, "dine_record_payment refused for $checkId ($ecrRefNum): ${r.message}")
            else Log.i(TAG, "Check $checkId paid $amountCents ($ecrRefNum), balance ${r.balance_cents}, closed=${r.closed}")
            r.success
        } catch (e: Exception) {
            Log.e(TAG, "dine_record_payment failed for $checkId: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------- splitting

    /**
     * Food + tax for some of a check's lines (split by items): tax per rate on
     * just those lines. Paying the last unpaid lines takes the exact balance,
     * so per-line rounding can't leave a cent open.
     */
    fun amountForItems(detail: CheckDetail, itemIds: Collection<String>): Int {
        val picked = detail.unpaidItems.filter { it.id in itemIds }
        if (picked.isEmpty()) return 0
        if (picked.size == detail.unpaidItems.size) return detail.balanceCents
        val sub = picked.sumOf { it.lineTotalCents }
        return (sub + SalesTax.cents(picked.map { it.lineTotalCents to it.tax_rate_bps })).coerceAtMost(detail.balanceCents)
    }

    /**
     * One of [ways] equal shares of what's still owed when the split started
     * ([startBalance]); the last share ([sharesLeft] == 1) takes whatever is left.
     */
    /**
     * Tax inside a payment of [amount]: lines paid on their own ([lines]: total to
     * rate) carry their own tax; a share carries the tax still owed in proportion;
     * the payment that settles the check takes exactly the tax left -- so a split
     * check's payments always add up to the check's tax (HST remittance).
     */
    fun taxOfPayment(detail: CheckDetail, amount: Int, lines: List<Pair<Int, Int>>?): Int {
        val taxLeft = (detail.taxCents - detail.paidTaxCents).coerceAtLeast(0)
        return when {
            amount >= detail.balanceCents -> taxLeft
            lines != null -> SalesTax.cents(lines).coerceAtMost(minOf(amount, taxLeft))
            detail.balanceCents == 0 -> 0
            else -> ((amount.toLong() * taxLeft + detail.balanceCents / 2) / detail.balanceCents).toInt()
        }
    }

    fun equalShare(balanceNow: Int, startBalance: Int, ways: Int, sharesLeft: Int): Int =
        if (sharesLeft <= 1) balanceNow else minOf(balanceNow, (startBalance + ways - 1) / ways)
}
