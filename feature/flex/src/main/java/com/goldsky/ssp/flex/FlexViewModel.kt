package com.goldsky.ssp.flex

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.goldsky.ssp.DeviceAdapter
import com.goldsky.ssp.flex.logic.FlexController
import com.goldsky.ssp.flex.logic.FlexOrder
import com.goldsky.ssp.flex.logic.FlexPayment
import com.goldsky.ssp.flex.logic.FlexRecovery
import com.goldsky.ssp.flex.logic.FlexSyncWorker
import com.goldsky.ssp.flex.logic.OrderRepository
import com.goldsky.ssp.flex.logic.SaleReason
import com.goldsky.ssp.flex.logic.VipScan
import com.goldsky.ssp.model.forVertical
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.DiagnosticManager
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.payment.VipDeductResult
import com.goldsky.ssp.payment.VipRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Aegis Flex (owner, 2026-11-05): the customer picks on the terminal and pays there; the
 * partner's own controller ([FlexController]) hands the product over. Started from Aegis Vend's
 * showcase grid; the money rules are Vend's:
 *  - the sale is journaled before the card is asked for, so a crash is given back on the next
 *    start (FlexRecovery);
 *  - approved, then the controller is told; only its "dispensed" keeps the money -- anything
 *    else reverses the sale (void, else refund), never charged-but-unfulfilled;
 *  - a reversal that fails goes to Cael as PAID / FAILED (CRITICAL) for a refund by hand.
 * A VIP member code pays the same way: deducted, then given back (vip_refund) if not dispensed.
 */
class FlexViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * [number]: shown on its tile. [quantity]: units the controller hands over (attributes.quantity,
     * else 1). [art]: a drawn, animated picture for the tile (attributes.art, e.g. "firewood"),
     * used before [imageUrl].
     */
    data class Item(
        val number: String, val name: String, val cents: Int,
        val imageUrl: String? = null, val productId: String? = null, val quantity: Int = 1, val art: String? = null,
    )

    sealed class State {
        /** The terminal can't sell: no payment app, no products from Cael, or locked. */
        object Unavailable : State()
        /** The grid; what's picked is [selected], so picking doesn't redraw the screen. */
        object Choosing : State()
        data class Authorizing(val item: Item) : State()
        /** The VIP member code is being scanned. */
        data class Scanning(val item: Item) : State()
        data class Dispensing(val item: Item) : State()
        /** Handed over, [item]'s price charged; [vip] = paid from a VIP card, whose balance is now [vipBalanceCents]. */
        data class Done(val item: Item, val vip: Boolean = false, val vipBalanceCents: Int? = null) : State()
        /** Paid but not handed over: [givenBack] false = the reversal failed, a refund by hand is owed. */
        data class NotDispensed(val item: Item, val givenBack: Boolean, val vip: Boolean = false) : State()
        /** [reason] null = the card was declined. */
        data class Declined(val item: Item, val reason: SaleReason? = null) : State()
    }

    private val _state = MutableStateFlow<State>(State.Unavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _selected = MutableStateFlow<Item?>(null)
    val selected: StateFlow<Item?> = _selected.asStateFlow()

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _french = MutableStateFlow(false)
    val french: StateFlow<Boolean> = _french.asStateFlow()

    /** Cael's payment methods for this store (KioskSettings.payment_method_mode), as Wash / Vend. */
    private val _payMode = MutableStateFlow(com.goldsky.ssp.model.PaymentMethodMode.CARD_ONLY)
    val payMode: StateFlow<Int> = _payMode.asStateFlow()

    /** The partner's controller, set in the technician panel (FlexController.load). */
    var controller: FlexController = FlexController.load(application)
        private set

    /** Is the controller there and ready (null = none set up)? Checked every [CONTROLLER_CHECK_MS]. */
    private val _controllerReady = MutableStateFlow<Boolean?>(null)
    val controllerReady: StateFlow<Boolean?> = _controllerReady.asStateFlow()

    /** The last check's words, for the technician panel. */
    private val _controllerDetail = MutableStateFlow("")
    val controllerDetail: StateFlow<String> = _controllerDetail.asStateFlow()

    private val onQ3mini = DeviceAdapter.getModel() == DeviceAdapter.HardwareModel.WIZARPOS_Q3MINI
    /** Emulator (debuggable, off the Q3mini): a stand-in payment, nothing recorded. */
    val simulated = !onQ3mini &&
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val payment: FlexPayment? = when {
        onQ3mini -> runCatching { FlexPayment.Real(PaymentProviderFactory.getPaymentProvider(application, "WIZARPOS")) }
            .onFailure { Log.e(TAG, "No WIZARPOS payment provider: ${it.message}") }.getOrNull()
        simulated -> FlexPayment.Simulated()
        else -> null
    }

    private var timer: Job? = null
    private var locked = false
    /** Another of this app's screens is in front (VIP page, technician checks): no sales meanwhile. */
    private var away = false

    /** Nothing under way: ads, the technician panel and remote recalls only then. */
    val idle: Boolean get() = _state.value.let { it is State.Choosing || it is State.Unavailable }

    init {
        OrderRepository.init(application)
        if (payment != null && !simulated) {
            FlexRecovery.run(application, payment) { triggerSync() }
            triggerSync()
        }
        applyAvailability()
        watchController()
    }

    /** Technician panel / Cael (SET_CONTROLLER): a new controller address (blank = none). Checked at once. */
    fun setController(url: String, token: String) {
        FlexController.save(getApplication(), url, token)
        controller = FlexController.load(getApplication())
        Log.i(TAG, "Controller: ${controller.description}")
        _controllerReady.value = null
        _controllerVersion.value++
        viewModelScope.launch { checkController() }
    }

    /** Bumped on every change of [controller], so the screens redraw with it. */
    private val _controllerVersion = MutableStateFlow(0)
    val controllerVersion: StateFlow<Int> = _controllerVersion.asStateFlow()

    /**
     * A controller that's off or unreachable means nothing can be handed over: the grid goes
     * Unavailable until it answers again (a sale in progress is left alone).
     */
    private fun watchController() {
        viewModelScope.launch {
            while (true) {
                checkController()
                delay(CONTROLLER_CHECK_MS)
            }
        }
    }

    suspend fun checkController() {
        val c = controller
        if (!c.connected) {
            _controllerReady.value = null
            _controllerDetail.value = ""
        } else {
            val ready = c.ready { _controllerDetail.value = it } == true
            if (ready != _controllerReady.value) Log.i(TAG, "Controller ${if (ready) "ready" else "NOT ready"}: ${_controllerDetail.value}")
            _controllerReady.value = ready
        }
        applyAvailability()
    }

    /**
     * The store's products from Cael (those with a price), in slot order and numbered by
     * attributes.slot, else in order. Flex has no vertical of its own in the database yet
     * (products / devices CHECK constraints): it sells the store's VEND products for now.
     */
    fun refreshItems() {
        _items.value = ConfigManager.getConfig()?.products.orEmpty().forVertical(PRODUCT_VERTICAL)
            .filter { it.is_active && it.price_cents > 0 }
            .sortedWith(compareBy(nullsLast(SLOT_ORDER)) { it.slot })
            .mapIndexed { i, p ->
                val qty = p.attributes?.get("quantity")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: 1
                val art = p.attributes?.get("art")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                Item(p.slot ?: number(i), p.name, p.price_cents, p.image_url, p.id, qty, art)
            }
        if (_selected.value !in _items.value) _selected.value = null
        applyAvailability()
    }

    fun refreshPayMode() {
        _payMode.value = ConfigManager.getConfig()?.settings?.payment_method_mode
            ?: com.goldsky.ssp.model.PaymentMethodMode.CARD_ONLY
        Log.i(TAG, "Payment methods: ${_payMode.value} (0 card + scan, 1 card, 2 scan)")
    }

    /** Remote LOCK / admin deactivation from Cael: no new sales while locked. */
    fun setLocked(locked: Boolean) {
        this.locked = locked
        applyAvailability()
    }

    fun setAway(on: Boolean) {
        away = on
        applyAvailability()
    }

    fun toggleLanguage() {
        _french.value = !_french.value
    }

    /** Idle <-> Unavailable; a sale in progress is left alone. */
    private fun applyAvailability() {
        val controllerOk = !controller.connected || _controllerReady.value == true
        val canSell = payment != null && _items.value.isNotEmpty() && !locked && !away && controllerOk
        val s = _state.value
        if (!canSell && s is State.Choosing) {
            _selected.value = null
            _state.value = State.Unavailable
        } else if (canSell && s is State.Unavailable) {
            _state.value = State.Choosing
        }
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
        val ref = newRef()
        journal(FlexOrder(
            orderId = ref, amountCents = item.cents, slot = item.name.take(40),
            paymentStatus = "PENDING", dispenseStatus = "PENDING", kind = "SALE", productId = item.productId,
        ))
        _state.value = State.Authorizing(item)
        pay.sale(item.cents, ref) { r ->
            when (r) {
                is FlexPayment.Result.Approved -> viewModelScope.launch {
                    if (!simulated) OrderRepository.update(getApplication(), ref) {
                        it.copy(paymentStatus = "PAID", authCode = r.authCode, entryMode = r.entryMode,
                            cardBrand = r.card?.brand, cardAid = r.card?.aid, cardBin = r.card?.bin)
                    }
                    _state.value = State.Dispensing(item)
                    if (dispense(ref, item, "CARD")) {
                        finishOrder(ref, "SUCCESS", "PAID")
                        show(State.Done(item))
                    } else {
                        pay.reverse(ref, item.cents) { ok -> notDispensed(ref, item, ok) }
                    }
                }
                FlexPayment.Result.Declined -> {
                    if (!simulated) OrderRepository.remove(getApplication(), ref)
                    show(State.Declined(item))
                }
                // No answer from the payment app: left PENDING in the journal, and
                // FlexRecovery reverses it on the next start if it was charged after all.
                is FlexPayment.Result.Failed -> show(State.Declined(item))
            }
        }
    }

    /** VIP: scan the member code, deduct the price, dispense; not dispensed = given back. */
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
                show(State.Declined(item, if (code == null) SaleReason.NO_CODE else SaleReason.VIP_INVALID))
                return@launch
            }
            val ref = newRef()
            // Journaled first: a crash in between is given back by FlexRecovery.
            journal(FlexOrder(
                orderId = ref, amountCents = item.cents, slot = item.name.take(40),
                paymentStatus = "PENDING", dispenseStatus = "PENDING", kind = "VIP", vipCardUid = uid, entryMode = "QR",
                productId = item.productId,
            ))
            when (val r = VipRepository.deduct(uid, item.cents, ref)) {
                is VipDeductResult.Success -> {
                    if (!simulated) OrderRepository.update(getApplication(), ref) { it.copy(paymentStatus = "PAID") }
                    _state.value = State.Dispensing(item)
                    if (dispense(ref, item, "VIP")) {
                        finishOrder(ref, "SUCCESS", "PAID")
                        show(State.Done(item, vip = true, vipBalanceCents = r.newBalanceCents))
                    } else {
                        notDispensed(ref, item, VipRepository.refund(uid, ref), vip = true)
                    }
                }
                is VipDeductResult.Rejected -> {
                    if (!simulated) OrderRepository.remove(getApplication(), ref)
                    show(State.Declined(item,
                        if (r.reason == "insufficient_balance") SaleReason.VIP_LOW_BALANCE else SaleReason.VIP_INVALID))
                }
                VipDeductResult.NetworkError -> {
                    // Unclear: give back whatever this order took (nothing, if it never arrived).
                    if (VipRepository.refund(uid, ref) && !simulated) OrderRepository.remove(getApplication(), ref)
                    show(State.Declined(item, SaleReason.VIP_OFFLINE))
                }
            }
        }
    }

    /** CANCEL while the code is being scanned. */
    fun cancel() {
        val s = _state.value as? State.Scanning ?: return
        timer?.cancel()
        VipScan.stop(getApplication())
        show(State.Declined(s.item, SaleReason.CANCELLED))
    }

    private suspend fun dispense(ref: String, item: Item, method: String): Boolean =
        runCatching {
            controller.dispense(FlexController.DispenseRequest(
                order_id = ref, product_id = item.productId, slot = item.number, name = item.name,
                quantity = item.quantity, amount_cents = item.cents,
                currency = if (com.goldsky.ssp.common.CoreConfig.region == "CA") "CAD" else "USD",
                payment_method = method, terminal_sn = DeviceRepository.getPersistedDeviceSn().orEmpty(),
                deadline_ms = System.currentTimeMillis() + FlexController.DISPENSE_WINDOW_MS,
            ))
        }
            .onFailure { Log.e(TAG, "Controller failed on $ref: ${it.message}") }
            .getOrDefault(false)

    /** Paid, not handed over: [givenBack] = voided / refunded (to the card or the VIP balance). */
    private fun notDispensed(ref: String, item: Item, givenBack: Boolean, vip: Boolean = false) {
        if (givenBack) {
            finishOrder(ref, "FAILED", "VOIDED")
        } else if (vip) {
            // Left PAID / PENDING: FlexRecovery gives the balance back on the next start.
            Log.e(TAG, "VIP $ref not given back yet -- FlexRecovery retries it")
        } else {
            Log.e(TAG, "Sale $ref not dispensed and not reversed -- refund it by hand")
            finishOrder(ref, "FAILED", "PAID")
            DeviceRepository.getPersistedDeviceSn()?.let {
                DiagnosticManager.reportError(it, "FLEX_REVERSE_FAILED", severity = "CRITICAL",
                    trace = "Sale $ref (${item.cents} cents) not dispensed and not reversed: refund by hand")
            }
        }
        show(State.NotDispensed(item, givenBack, vip))
    }

    private fun finishOrder(ref: String, dispense: String, payment: String) {
        if (simulated) return
        OrderRepository.updateOrder(getApplication(), ref, dispense, payment)
        triggerSync()
    }

    /** Written before the card is asked for, so a crash can't lose a payment (FlexRecovery). */
    private fun journal(order: FlexOrder) {
        if (!simulated) OrderRepository.saveOrder(getApplication(), order)
    }

    private fun triggerSync() {
        WorkManager.getInstance(getApplication()).enqueue(OneTimeWorkRequestBuilder<FlexSyncWorker>().build())
    }

    private fun show(state: State) {
        _state.value = state
        timer?.cancel()
        timer = viewModelScope.launch {
            delay(RESULT_MS)
            if (_state.value == state) {
                _state.value = State.Choosing
                _french.value = false
                applyAvailability()
            }
        }
    }

    private fun newRef() = "FLX-${System.currentTimeMillis()}"

    /** 01, 02, ... for items without a number of their own. */
    private fun number(i: Int) = (i + 1).toString().padStart(2, '0')

    private companion object {
        const val TAG = "FlexVM"
        /** See [refreshItems]. */
        const val PRODUCT_VERTICAL = "VEND"
        const val CHOICE_MS = 60_000L
        const val RESULT_MS = 6_000L
        const val CONTROLLER_CHECK_MS = 10_000L

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
