package com.goldsky.ssp.feature.retail.arke

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.model.KitchenLine
import com.goldsky.ssp.model.KitchenTicket
import com.goldsky.ssp.payment.ArkeOrderRepository
import com.goldsky.ssp.payment.ArkeOrderRepository.Order
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.ReceiptPrinterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Arke online orders on the store's terminal (owner 2026-11-16; the store's own delivery
 * 2026-11-17: ready -> out with a driver -> delivered, or brought back): paid orders come
 * in from the store's link, the terminal chimes, staff accept with a ready time (the
 * kitchen ticket prints), mark it ready, then picked up -- or reject it, and the money
 * goes back to the customer's card (arke-pay-refund). Every terminal of the store sees
 * the same orders; the list refreshes every few seconds while the app runs.
 */
class ArkeViewModel : ViewModel() {

    var orders by mutableStateOf<List<Order>>(emptyList())
        private set
    /** false = the cloud couldn't be reached on the last refresh. */
    var online by mutableStateOf(true)
        private set
    /** Bumped when a new paid order arrives, so the screen can chime once. */
    var arrivals by mutableStateOf(0)
        private set
    /** The newest order that just came in (for a banner), until dismissed or handled. */
    var latestArrival by mutableStateOf<Order?>(null)
        private set
    /** The order a step is running on. */
    var busyId by mutableStateOf<String?>(null)
        private set
    /** A short message for staff (a refusal, a refund's outcome); cleared by [clearMessage]. */
    var message by mutableStateOf<String?>(null)
        private set

    val newCount: Int get() = orders.count { it.status == ArkeOrderRepository.NEW }

    /** Orders already seen; null until the first read (what's already there doesn't chime). */
    private var seen: MutableSet<String>? = null
    private var poller: Job? = null

    fun clearMessage() { message = null }
    fun dismissArrival() { latestArrival = null }

    fun startRefreshing() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive) {
                refreshNow()
                delay(8000)
            }
        }
    }

    suspend fun refreshNow() {
        val list = ArkeOrderRepository.orders()
        online = list != null
        if (list == null) return
        val known = seen
        if (known == null) {
            seen = list.map { it.id }.toMutableSet()
        } else {
            val fresh = list.filter { it.id !in known && it.status == ArkeOrderRepository.NEW }
            known += list.map { it.id }
            if (fresh.isNotEmpty()) {
                arrivals++
                latestArrival = fresh.maxByOrNull { it.paid_at ?: "" }
            }
        }
        orders = list
        if (latestArrival?.let { a -> list.none { it.id == a.id && it.status == ArkeOrderRepository.NEW } } == true) latestArrival = null
    }

    private fun run(context: Context, o: Order, block: suspend () -> ArkeOrderRepository.Result, after: suspend (Order?) -> Unit = {}) {
        if (busyId != null) return
        busyId = o.id
        viewModelScope.launch {
            val r = block()
            if (r.ok) after(r.order)
            else message = if (r.message == null) context.getString(R.string.arke_step_offline) else context.getString(R.string.arke_step_failed, r.message)
            refreshNow()
            busyId = null
        }
    }

    /** Accept with [minutes] until ready; the kitchen ticket prints. */
    fun accept(context: Context, o: Order, minutes: Int, staffName: String?) =
        run(context, o, { ArkeOrderRepository.accept(o.id, minutes, staffName) }) { now -> printTicket(context, now ?: o, staffName) }

    fun ready(context: Context, o: Order, staffName: String?) = run(context, o, { ArkeOrderRepository.ready(o.id, staffName) })

    fun pickedUp(context: Context, o: Order, staffName: String?) = run(context, o, { ArkeOrderRepository.pickedUp(o.id, staffName) })

    /** The driver (the staff signed in here) takes a delivery out. */
    fun out(context: Context, o: Order, staffName: String?) = run(context, o, { ArkeOrderRepository.out(o.id, staffName) })

    fun delivered(context: Context, o: Order, staffName: String?) = run(context, o, { ArkeOrderRepository.delivered(o.id, staffName) })

    /** Couldn't hand it over: back to Ready, [reason] kept. */
    fun back(context: Context, o: Order, reason: String, staffName: String?) =
        run(context, o, { ArkeOrderRepository.back(o.id, reason, staffName) })

    /** Reject with [reason] (the customer sees it), then give the card its money back. */
    fun reject(context: Context, o: Order, reason: String, staffName: String?) =
        run(context, o, { ArkeOrderRepository.reject(o.id, reason, staffName) }) {
            val code = o.pickup_code ?: ""
            val refund = ArkeOrderRepository.refund(o.id)
            message = if (refund.status != null) context.getString(R.string.arke_refunded, code)
                      else context.getString(R.string.arke_refund_failed, code, refund.reason ?: refund.error ?: "?")
        }

    fun reprint(context: Context, o: Order, staffName: String?) {
        viewModelScope.launch { printTicket(context, o, staffName) }
    }

    private suspend fun printTicket(context: Context, o: Order, staffName: String?) {
        val ticket = ticketOf(o, staffName)
        withContext(Dispatchers.IO) {
            ReceiptPrinterManager.printKitchenTicket(context, ticket, DeviceRepository.getPersistedHardwareVendor())
        }
    }

    companion object {
        /** Where a delivery goes, as printed for the driver: street, area, the driver's note, the phone. */
        fun deliveryLines(o: Order): List<String>? {
            val a = o.delivery_address ?: return null
            return listOfNotNull(a.street.takeIf { it.isNotBlank() }, a.area.takeIf { it.isNotBlank() },
                a.instructions?.takeIf { it.isNotBlank() }?.let { "* $it" }, o.customer_phone?.let { "Tel $it" })
        }

        /** The kitchen's copy of an online order: its number, the customer's name, dishes, note (+ the address for a delivery). */
        fun ticketOf(o: Order, staffName: String?) = KitchenTicket(
            number = o.pickup_code?.drop(1)?.toIntOrNull() ?: 0,
            takeout = true,
            note = o.note ?: "",
            lines = o.items.map { KitchenLine(it.name, it.quantity, it.options, it.note) },
            staffName = staffName,
            onlineCode = o.pickup_code ?: "A?",
            customer = o.customer_name.takeIf { it.isNotBlank() },
            deliveryTo = if (o.isDelivery) deliveryLines(o) else null,
            platform = if (o.isUber) "UBER EATS" else null
        )
    }
}
