package com.goldsky.ssp.feature.retail.dine

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.model.KitchenLine
import com.goldsky.ssp.model.KitchenTicket
import com.goldsky.ssp.model.SaleLine
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.DineRepository
import com.goldsky.ssp.payment.ReceiptPrinterManager
import com.goldsky.ssp.payment.SalesTax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dine-in on one terminal: the floor, the check being worked on, and the
 * round being taken (the [RetailViewModel] cart -- the same product screen,
 * options and notes as a counter sale). Every other terminal sees the same
 * checks: the floor and the open check refresh every few seconds.
 */
class DineViewModel : ViewModel() {

    var floor by mutableStateOf<List<DineRepository.FloorTable>?>(null)
        private set
    /** Customers' QR orders waiting for staff (Ourea Scan). */
    var scanPending by mutableStateOf<List<DineRepository.ScanOrder>>(emptyList())
        private set
    /** Bumped when a new QR order arrives, so the screen can chime once. */
    var scanArrivals by mutableStateOf(0)
        private set
    /** null = cloud unreachable on the last refresh. */
    var online by mutableStateOf(true)
        private set
    var check by mutableStateOf<DineRepository.CheckDetail?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** A short message for the cashier (refusal or connection problem); cleared by [clearMessage]. */
    var message by mutableStateOf<String?>(null)
        private set

    private var poller: Job? = null
    /** For the kitchen tickets of orders approved in Cael (accepted here without a tap). */
    private var appContext: Context? = null
    /** Approved orders this terminal already tried, so a refusal isn't retried every 4 s. */
    private val approvedTried = mutableSetOf<String>()

    fun clearMessage() { message = null }

    /** Keep the floor and the open check fresh while a dine-in screen shows. */
    fun startRefreshing(context: Context) {
        appContext = context.applicationContext
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive) {
                refreshNow()
                delay(4000)
            }
        }
    }

    fun stopRefreshing() { poller?.cancel(); poller = null }

    suspend fun refreshNow() {
        val f = DineRepository.floor()
        online = f != null
        if (f != null) {
            floor = f.tables
            if (f.scanPending.any { new -> scanPending.none { it.id == new.id } }) scanArrivals++
            scanPending = f.scanPending
            acceptApproved(f.scanPending)
        }
        check?.let { c ->
            DineRepository.check(c.check.id)?.let { fresh ->
                // Closed elsewhere (paid at another terminal, or voided): back to the floor.
                check = if (fresh.check.isOpen) fresh else null
            }
        }
    }

    private fun refresh() { viewModelScope.launch { refreshNow() } }

    /** Seat [table] (or join its open check) and open the check. */
    fun seat(table: DineRepository.DiningTable, guests: Int, staffId: String?, staffName: String?) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val r = DineRepository.openCheck(table.id, guests, staffId, staffName)
            val id = r.id
            if (r.ok && id != null) openCheck(id) else message = r.message ?: OFFLINE
            busy = false
        }
    }

    fun openCheck(checkId: String) {
        viewModelScope.launch {
            val d = DineRepository.check(checkId)
            if (d == null) message = OFFLINE else check = d
        }
    }

    /** Back to the floor (the round being taken stays in the cart). */
    fun closeCheck() { check = null; refresh() }

    /**
     * Sends the cart to the kitchen as the next round of the open check: saved
     * to the cloud first (the round number comes back), then the kitchen
     * ticket, then the cart is cleared. Nothing prints if the save fails.
     */
    fun sendRound(context: Context, cart: RetailViewModel, staffName: String?) {
        val c = check ?: return
        if (busy || cart.cart.isEmpty()) return
        busy = true
        val fallbackBps = SalesTax.deviceRateBps()
        val lines = cart.cart.map { item ->
            DineRepository.NewLine(
                productId = item.product.id, name = item.product.name,
                options = item.selectedModifiers.map { it.name }, quantity = item.quantity,
                unitPriceCents = item.unitPriceCents, taxRateBps = item.product.taxRateBps ?: fallbackBps
            )
        }
        val roundNote = cart.orderNote
        viewModelScope.launch {
            val r = DineRepository.sendItems(c.check.id, lines, staffName)
            val round = r.round
            if (r.ok && round != null) {
                val ticket = KitchenTicket(
                    number = c.check.check_no, takeout = false, note = roundNote,
                    lines = lines.map { KitchenLine(it.name, it.quantity, it.options) },
                    staffName = staffName, table = c.table?.name ?: "?", round = round, guests = c.check.guests
                )
                withContext(Dispatchers.IO) {
                    ReceiptPrinterManager.printKitchenTicket(context, ticket, DeviceRepository.getPersistedHardwareVendor())
                }
                cart.clearCart()
                refreshNow()
            } else {
                message = r.message ?: OFFLINE
            }
            busy = false
        }
    }

    /**
     * Accepts a customer's QR order: it becomes the table's next round, then the
     * kitchen ticket prints here (or on the kitchen printer) like any round.
     */
    fun acceptScan(context: Context, order: DineRepository.ScanOrder, staffName: String?) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val r = DineRepository.acceptScan(order.id, staffName)
            when {
                r == null -> message = OFFLINE
                !r.success -> message = r.message ?: OFFLINE
                else -> withContext(Dispatchers.IO) {
                    val ticket = KitchenTicket(
                        number = r.check_no, takeout = false, note = listOfNotNull("扫码 QR", r.note).joinToString(" · "),
                        lines = r.items.map { KitchenLine(it.name, it.quantity, it.options, it.note) },
                        staffName = staffName, table = r.table_name, round = r.round, guests = r.guests
                    )
                    ReceiptPrinterManager.printKitchenTicket(context, ticket, DeviceRepository.getPersistedHardwareVendor())
                }
            }
            refreshNow()
            busy = false
        }
    }

    /**
     * Orders a manager approved in Cael: the browser can't print in the kitchen,
     * so the first terminal showing the floor accepts them (dine_accept_scan
     * locks the order -- another terminal that got there first makes this one's
     * call fail with "order_accepted", which is fine) and prints the ticket.
     */
    private suspend fun acceptApproved(pending: List<DineRepository.ScanOrder>) {
        val context = appContext ?: return
        for (order in pending.filter { it.approved_at != null && it.id !in approvedTried }) {
            approvedTried += order.id
            val r = DineRepository.acceptScan(order.id, order.approved_by) ?: run { approvedTried -= order.id; return }
            if (!r.success) continue
            withContext(Dispatchers.IO) {
                val ticket = KitchenTicket(
                    number = r.check_no, takeout = false, note = listOfNotNull("扫码 QR", r.note).joinToString(" · "),
                    lines = r.items.map { KitchenLine(it.name, it.quantity, it.options, it.note) },
                    staffName = order.approved_by, table = r.table_name, round = r.round, guests = r.guests
                )
                ReceiptPrinterManager.printKitchenTicket(context, ticket, DeviceRepository.getPersistedHardwareVendor())
            }
        }
    }

    fun rejectScan(order: DineRepository.ScanOrder, staffName: String?, reason: String?) {
        viewModelScope.launch {
            val r = DineRepository.rejectScan(order.id, staffName, reason)
            if (!r.ok) message = r.message ?: OFFLINE
            refreshNow()
        }
    }

    /** Prints the bill (pre-payment check) here and marks the check BILLED. */
    fun printBill(context: Context) {
        val c = check ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ReceiptPrinterManager.printLines(context, billLines(c), DeviceRepository.getPersistedHardwareVendor())
            }
            DineRepository.updateCheck(c.check.id, status = "BILLED")
            refreshNow()
        }
    }

    fun voidItem(item: DineRepository.Item, approverName: String?) {
        viewModelScope.launch {
            val r = DineRepository.voidItem(item.id, approverName)
            if (!r.ok) message = r.message ?: OFFLINE
            refreshNow()
        }
    }

    fun setGuests(guests: Int) {
        val c = check ?: return
        viewModelScope.launch { DineRepository.updateCheck(c.check.id, guests = guests); refreshNow() }
    }

    /** A table seated by mistake (nothing sent, nothing paid). */
    fun cancelEmptyCheck() {
        val c = check ?: return
        viewModelScope.launch {
            val r = DineRepository.updateCheck(c.check.id, status = "VOID")
            if (r.ok) { check = null; refreshNow() } else message = r.message ?: OFFLINE
        }
    }

    /** After a payment on the check went through (call off the main thread is fine). */
    suspend fun paymentDone(context: Context, pay: CheckPay, ecrRefNum: String, tipCents: Int, method: String, paidBy: String?) {
        DineRepository.recordPayment(context, pay.checkId, ecrRefNum, pay.amountCents, pay.taxCents, tipCents, method, paidBy, pay.itemIds)
        withContext(Dispatchers.Main) { refreshNow() }
    }

    companion object {
        const val OFFLINE = "offline"

        /**
         * The bill: lines with HST, what's been paid, the balance, and a tip
         * guide on the pre-tax amount (the usual Canadian bill footer).
         */
        fun billLines(d: DineRepository.CheckDetail): List<String> {
            fun money(c: Int) = "%.2f".format(c / 100.0)
            val out = mutableListOf<String>()
            out += "      ${DeviceRepository.getStoreName()}"
            out += "--------------------------------"
            out += "      账单  BILL"
            out += "桌 Table ${d.table?.name ?: "?"}   #${d.check.check_no}"
            out += "${d.check.guests} 位 guests   ${d.check.server_name ?: ""}"
            out += "--------------------------------"
            out += ReceiptPrinterManager.itemLines(d.liveItems.map { SaleLine(it.displayName, it.quantity, it.unit_price_cents, it.tax_rate_bps) })
            out += "--------------------------------"
            out += ReceiptPrinterManager.row("SUBTOTAL", money(d.subtotalCents))
            out += ReceiptPrinterManager.row("TAX", money(d.taxCents))
            out += ReceiptPrinterManager.row("TOTAL", money(d.totalCents))
            if (d.paidCents > 0) {
                out += ReceiptPrinterManager.row("PAID", money(d.paidCents))
                out += ReceiptPrinterManager.row("BALANCE DUE", money(d.balanceCents))
            }
            out += "--------------------------------"
            out += "Tip guide (before tax):"
            listOf(15, 18, 20).forEach { pct ->
                out += ReceiptPrinterManager.row("  $pct%", money((d.subtotalCents * pct + 50) / 100))
            }
            out += "--------------------------------"
            out += "      Thank you! 谢谢光临"
            return out
        }
    }
}

/**
 * One payment on a check: [amountCents] is food + tax (tip comes on top);
 * [itemIds] = split by items; [label] goes on the receipt ("Share 1/3").
 */
data class CheckPay(
    val checkId: String,
    val amountCents: Int,
    val itemIds: List<String>?,
    val lines: List<SaleLine>,
    val label: String,
    val tableName: String,
    val checkNo: Int,
    /** The amount's tax part (transaction row, HST report); see [taxOf]. */
    val taxCents: Int = 0
) {
    companion object {
        /**
         * Tax inside a payment of [amount] on [detail]: lines paid on their own carry
         * their own tax; a share carries the tax still owed in proportion; the payment
         * that settles the check takes exactly the tax left -- so a split check's
         * payments always add up to the check's tax (HST remittance).
         */
        fun taxOf(detail: DineRepository.CheckDetail, amount: Int, lines: List<SaleLine>?): Int =
            DineRepository.taxOfPayment(detail, amount, lines?.map { it.lineTotalCents to it.taxRateBps })
    }

    /** Kept for callers: the tax part decided when the payment was chosen. */
    fun taxPart(@Suppress("UNUSED_PARAMETER") detail: DineRepository.CheckDetail): Int = taxCents

    /**
     * The bill the customer checks before the tip (Ourea's Eye, Iris handed over): at a
     * table the order was taken earlier, so this is the first time they see it. Paying
     * chosen dishes: those dishes. The balance or an even share: the whole bill, what was
     * paid already, then this payment -- so every figure adds up. [title]: "Table T1 · #6".
     */
    fun billReview(detail: DineRepository.CheckDetail?, title: String): com.goldsky.ssp.common.Tipping.Review {
        val due = amountCents
        val sub = amountCents - taxCents
        return if (itemIds != null || detail == null) com.goldsky.ssp.common.Tipping.Review(
            title = listOf(title, label).filter { it.isNotBlank() }.joinToString(" · "),
            lines = lines.map { com.goldsky.ssp.common.Tipping.ReviewLine(it.name, it.quantity, it.lineTotalCents) },
            subtotalCents = sub, taxCents = taxCents, totalCents = due
        ) else com.goldsky.ssp.common.Tipping.Review(
            title = title,
            lines = detail.liveItems.map { com.goldsky.ssp.common.Tipping.ReviewLine(it.displayName, it.quantity, it.lineTotalCents) },
            subtotalCents = detail.subtotalCents, taxCents = detail.taxCents, totalCents = detail.totalCents,
            paidCents = detail.paidCents, dueCents = due,
            dueLabel = if (due == detail.balanceCents) null else label.ifBlank { null }
        )
    }
}
