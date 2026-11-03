package com.goldsky.ssp.vending

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.DeviceAdapter
import com.goldsky.ssp.model.forVertical
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DiagnosticManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.vending.db.VendingOrder
import com.goldsky.ssp.vending.logic.OrderRepository
import com.goldsky.ssp.vending.logic.VendPayment
import com.goldsky.ssp.vending.logic.VendingState
import com.goldsky.ssp.vending.logic.VipScan
import com.goldsky.ssp.payment.VipDeductResult
import com.goldsky.ssp.payment.VipRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Showcase sale mode (owner, 2026-11-03): the customer picks from a 6-item grid on the card
 * terminal itself and pays there -- a terminal-centred way of selling, shown to prospects.
 * Real sites stay on MDB (the machine's own keypad); a terminal on MDB can't tell the
 * machine what to drop, so here nothing drops.
 *
 * Never a fake payment (the 253 incident, 2026-10-31): the card is really read and
 * authorized, then the sale is voided at once -- nothing charged. If the void fails the
 * row goes to Cael as PAID / FAILED for a refund by hand, as any failed vend; a crash in
 * between is given back by VendRecovery (the sale is journaled before the card is asked for).
 * Turned on only from the technician panel ([SaleMode]).
 *
 * VIP member code too (Cael payment methods with scan): the balance is really deducted, then
 * given straight back (vip_refund) -- the card demo's void, for a VIP card.
 */
class ShowcaseViewModel(application: Application) : AndroidViewModel(application) {

    /** [number]: the item's number, shown on its tile. */
    data class Item(val number: String, val name: String, val cents: Int, val emoji: String, val imageUrl: String? = null)

    sealed class State {
        /** The grid; what's picked is [selected], so picking doesn't redraw the screen. */
        object Choosing : State()
        data class Authorizing(val item: Item) : State()
        /** The VIP member code is being scanned. */
        data class Scanning(val item: Item) : State()
        data class Dispensing(val item: Item) : State()
        /** [voided] false: the void failed -- a refund by hand is owed. [vip]: paid with a VIP card, given back to it. */
        data class Done(val item: Item, val voided: Boolean, val vip: Boolean = false) : State()
        /** [reason]: why a VIP payment didn't go through (null = the card was declined). */
        data class Declined(val item: Item, val reason: VendingState.Reason? = null) : State()
    }

    private val _state = MutableStateFlow<State>(State.Choosing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _selected = MutableStateFlow<Item?>(null)
    val selected: StateFlow<Item?> = _selected.asStateFlow()

    private val _items = MutableStateFlow(samples())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val onQ3mini = DeviceAdapter.getModel() == DeviceAdapter.HardwareModel.WIZARPOS_Q3MINI
    private val simulated = !onQ3mini &&
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val payment: VendPayment? = when {
        onQ3mini -> runCatching { VendPayment.Real(PaymentProviderFactory.getPaymentProvider(application, "WIZARPOS")) }
            .onFailure { Log.e(TAG, "No WIZARPOS payment provider: ${it.message}") }.getOrNull()
        simulated -> VendPayment.Simulated()
        else -> null
    }

    private var timer: Job? = null

    /** Nothing under way: ads, the technician panel and remote recalls only then. */
    val idle: Boolean get() = _state.value is State.Choosing

    /**
     * The store's VEND products from Cael (those with a price; six to a screen, the rest by
     * scrolling), in slot order and numbered by attributes.slot, else in order; else the samples.
     * "VEND" is products.vertical_type's value (its CHECK constraint), not "VENDING".
     */
    fun refreshItems() {
        val fromCael = ConfigManager.getConfig()?.products.orEmpty().forVertical("VEND")
            .filter { it.is_active && it.price_cents > 0 }
            .sortedWith(compareBy(nullsLast(SLOT_ORDER)) { it.slot })
            .mapIndexed { i, p -> Item(p.slot ?: number(i), p.name, p.price_cents, "", p.image_url) }
        _items.value = if (fromCael.isNotEmpty()) fromCael else samples()
    }

    fun select(item: Item) {
        if (_state.value !is State.Choosing) return
        _selected.value = item
        // Walked away: clear the choice.
        timer?.cancel()
        timer = viewModelScope.launch {
            delay(CHOICE_MS)
            if (_selected.value == item) _selected.value = null
        }
    }

    fun pay() {
        val pay = payment ?: return
        if (_state.value != State.Choosing) return
        val item = _selected.value ?: return
        _selected.value = null
        timer?.cancel()
        val ref = "VND-${System.currentTimeMillis()}"
        journal(ref, item)
        _state.value = State.Authorizing(item)
        pay.sale(item.cents, ref) { r ->
            when (r) {
                is VendPayment.Result.Approved -> viewModelScope.launch {
                    if (!simulated) OrderRepository.update(getApplication(), ref) {
                        it.copy(paymentStatus = "PAID", authCode = r.authCode, entryMode = r.entryMode,
                            cardBrand = r.card?.brand, cardAid = r.card?.aid, cardBin = r.card?.bin)
                    }
                    // What a vend would look like; then the money goes straight back.
                    _state.value = State.Dispensing(item)
                    delay(DISPENSE_SHOW_MS)
                    pay.reverse(ref, item.cents) { ok -> finish(ref, item, ok) }
                }
                VendPayment.Result.Declined -> {
                    if (!simulated) OrderRepository.remove(getApplication(), ref)
                    show(State.Declined(item))
                }
                // No answer from the payment app: left PENDING in the journal, and
                // VendRecovery reverses it on the next start if it was charged after all.
                is VendPayment.Result.Failed -> show(State.Declined(item))
            }
        }
    }

    /** VIP: scan the member code, deduct the price, show the vend, give it straight back. */
    fun payVip() {
        if (_state.value != State.Choosing) return
        val item = _selected.value ?: return
        _selected.value = null
        timer?.cancel()
        val scanning = State.Scanning(item)
        _state.value = scanning
        timer = viewModelScope.launch {
            val code = VipScan.code(getApplication())
            if (_state.value !== scanning) return@launch
            val uid = code?.let { VipRepository.resolveCardUidByQrCode(it.trim()) }
            if (_state.value !== scanning) return@launch
            if (uid == null) {
                show(State.Declined(item, if (code == null) VendingState.Reason.NO_CODE else VendingState.Reason.VIP_INVALID))
                return@launch
            }
            val ref = "VND-${System.currentTimeMillis()}"
            // Journaled first: a crash in between is given back by VendRecovery.
            if (!simulated) OrderRepository.saveOrder(getApplication(), VendingOrder(
                orderId = ref, amountCents = item.cents, slot = "DEMO ${item.name}".take(40),
                paymentStatus = "PENDING", dispenseStatus = "PENDING", kind = "VIP", vipCardUid = uid, entryMode = "QR",
            ))
            when (val r = VipRepository.deduct(uid, item.cents, ref)) {
                is VipDeductResult.Success -> {
                    _state.value = State.Dispensing(item)
                    delay(DISPENSE_SHOW_MS)
                    val back = VipRepository.refund(uid, ref)
                    finish(ref, item, back, vip = true)
                }
                is VipDeductResult.Rejected -> {
                    if (!simulated) OrderRepository.remove(getApplication(), ref)
                    show(State.Declined(item,
                        if (r.reason == "insufficient_balance") VendingState.Reason.VIP_LOW_BALANCE else VendingState.Reason.VIP_INVALID))
                }
                VipDeductResult.NetworkError -> {
                    // Unclear: give back whatever this order took (nothing, if it never arrived).
                    if (VipRepository.refund(uid, ref) && !simulated) OrderRepository.remove(getApplication(), ref)
                    show(State.Declined(item, VendingState.Reason.VIP_OFFLINE))
                }
            }
        }
    }

    /** CANCEL while the code is being scanned. */
    fun cancel() {
        val s = _state.value as? State.Scanning ?: return
        timer?.cancel()
        VipScan.stop(getApplication())
        show(State.Declined(s.item, VendingState.Reason.CANCELLED))
    }

    private fun finish(ref: String, item: Item, voided: Boolean, vip: Boolean = false) {
        if (!simulated) {
            if (voided) {
                // Authorized and voided: nothing owed, nothing to report.
                OrderRepository.remove(getApplication(), ref)
            } else if (vip) {
                // Left unfinished (PAID / PENDING): VendRecovery gives the balance back on the next start.
                Log.e(TAG, "Showcase VIP $ref not given back yet -- VendRecovery retries it")
                OrderRepository.update(getApplication(), ref) { it.copy(paymentStatus = "PAID") }
            } else {
                Log.e(TAG, "Showcase sale $ref could not be voided -- refund it by hand")
                OrderRepository.updateOrder(getApplication(), ref, "FAILED", "PAID")
                DeviceRepository.getPersistedDeviceSn()?.let {
                    DiagnosticManager.reportError(it, "SHOWCASE_VOID_FAILED", severity = "CRITICAL",
                        trace = "Showcase sale $ref (${item.cents} cents) authorized but not voided: refund by hand")
                }
            }
        }
        show(State.Done(item, voided, vip))
    }

    private fun journal(ref: String, item: Item) {
        if (simulated) return
        OrderRepository.saveOrder(getApplication(), VendingOrder(
            orderId = ref, amountCents = item.cents, slot = "DEMO ${item.name}".take(40),
            paymentStatus = "PENDING", dispenseStatus = "PENDING", kind = "SALE",
        ))
    }

    private fun show(state: State) {
        _state.value = state
        timer?.cancel()
        timer = viewModelScope.launch {
            delay(RESULT_MS)
            if (_state.value == state) _state.value = State.Choosing
        }
    }

    private fun samples(): List<Item> {
        // The VMC-simulator (test) build: every live-card amount is $0.10 (owner, 2026-11-02).
        fun p(cents: Int) = if (com.goldsky.ssp.common.CoreConfig.vmcSimulator) 10 else cents
        // Emoji up to Unicode 9: the Q3mini runs Android 7.1.
        return listOf(
            Triple("Chips", 250, "🥔"),
            Triple("Water", 175, "💧"),
            Triple("Juice", 225, "🍹"),
            Triple("Candy", 150, "🍬"),
            Triple("Coffee", 300, "☕"),
            Triple("Chocolate", 275, "🍫"),
            Triple("Cookies", 200, "🍪"),
            Triple("Donut", 225, "🍩"),
            Triple("Apple", 125, "🍎"),
            Triple("Milk", 200, "🥛"),
            Triple("Green Tea", 250, "🍵"),
            Triple("Ice Cream", 350, "🍦"),
        ).mapIndexed { i, (name, cents, emoji) -> Item(number(i), name, p(cents), emoji) }
    }

    /** 01, 02, ... for items without a number of their own. */
    private fun number(i: Int) = (i + 1).toString().padStart(2, '0')

    private companion object {
        const val TAG = "VendShowcase"
        const val CHOICE_MS = 60_000L
        const val DISPENSE_SHOW_MS = 3_000L
        const val RESULT_MS = 6_000L

        /** Natural order: A2 before A10, 9 before 12 (runs of digits compared as numbers). */
        val SLOT_ORDER = Comparator<String> { a, b ->
            val split = Regex("\\d+|\\D+")
            val x = split.findAll(a.uppercase()).map { it.value }.toList()
            val y = split.findAll(b.uppercase()).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val nx = x[i].toBigIntegerOrNull()
                val ny = y[i].toBigIntegerOrNull()
                val c = if (nx != null && ny != null) nx.compareTo(ny) else x[i].compareTo(y[i])
                if (c != 0) return@Comparator c
            }
            x.size - y.size
        }
    }
}

/**
 * How this terminal sells, set in the technician panel (and, for MDB, which purchase flow): [MDB] = the vending machine's own
 * keypad (every real site), [SHOWCASE] = the 6-item grid on the terminal, for showing
 * prospects (card authorized, then voided).
 */
object SaleMode {
    enum class Mode { MDB, SHOWCASE }

    private fun prefs(context: Context) = context.getSharedPreferences("vend_sale_mode", Context.MODE_PRIVATE)

    fun get(context: Context): Mode =
        runCatching { Mode.valueOf(prefs(context).getString("mode", null) ?: "") }.getOrDefault(Mode.MDB)

    fun set(context: Context, mode: Mode) = prefs(context).edit().putString("mode", mode.name).apply()

    /** The MDB purchase flow (see [com.goldsky.ssp.vending.logic.PurchaseFlow]). */
    fun flow(context: Context): com.goldsky.ssp.vending.logic.PurchaseFlow = runCatching {
        com.goldsky.ssp.vending.logic.PurchaseFlow.valueOf(prefs(context).getString("flow", null) ?: "")
    }.getOrDefault(com.goldsky.ssp.vending.logic.PurchaseFlow.BOTH)

    fun setFlow(context: Context, flow: com.goldsky.ssp.vending.logic.PurchaseFlow) =
        prefs(context).edit().putString("flow", flow.name).apply()
}
