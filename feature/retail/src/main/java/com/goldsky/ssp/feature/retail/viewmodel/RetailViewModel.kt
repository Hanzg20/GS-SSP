package com.goldsky.ssp.feature.retail.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.common.FeedbackManager
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.db.ParkedOrderEntity
import com.goldsky.ssp.model.CartItem
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.model.ProductModifier
import com.goldsky.ssp.feature.retail.RetailRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One cart line as parked -- a plain List rather than the previous
 * Map<ProductId, Qty> so two lines for the same product with different
 * [ProductModifier]s (e.g. "Latte" vs. "Latte + Extra Shot", both product.id
 * "latte") don't collide into a single map entry and silently drop one
 * line's quantity, and so modifiers survive the round trip at all -- the
 * old Map<String, Int> shape had no room for them.
 */
@Serializable
private data class ParkedCartLine(
    val productId: String,
    val quantity: Int,
    val modifierIds: List<String> = emptyList(),
    val discount: com.goldsky.ssp.payment.Discount? = null
)

/**
 * Centrally manages the Shopping Cart and Scanner events for Retail Pro.
 */
class RetailViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "RetailViewModel"
    }

    private val _cart = mutableStateListOf<CartItem>()
    val cart: List<CartItem> get() = _cart

    private val _totalCents = MutableStateFlow(0)
    val totalCents: StateFlow<Int> = _totalCents.asStateFlow()

    private val _itemCount = MutableStateFlow(0)
    val itemCount: StateFlow<Int> = _itemCount.asStateFlow()

    /** Latest scan for on-screen feedback; [seq] makes a repeat scan of the same code a new value. */
    data class ScanFeedback(val barcode: String, val productName: String?, val seq: Long = System.nanoTime())

    private val _lastScan = MutableStateFlow<ScanFeedback?>(null)
    val lastScan: StateFlow<ScanFeedback?> = _lastScan.asStateFlow()

    private val _parkedOrders = MutableStateFlow<List<ParkedOrderEntity>>(emptyList())
    val parkedOrders: StateFlow<List<ParkedOrderEntity>> = _parkedOrders.asStateFlow()

    /**
     * Loads parked orders from DB.
     */
    fun loadParkedOrders(context: Context) {
        val db = LocalDatabase.getInstance(context)
        viewModelScope.launch(Dispatchers.IO) {
            _parkedOrders.value = db.parkedOrderDao().getAll()
        }
    }

    /**
     * Saves current cart to parked orders.
     */
    fun parkCurrentOrder(context: Context, tableName: String? = null) {
        if (_cart.isEmpty()) return
        val db = LocalDatabase.getInstance(context)
        val entity = currentCartAsParked(tableName)
        viewModelScope.launch(Dispatchers.IO) {
            db.parkedOrderDao().insert(entity)
            withContext(Dispatchers.Main) {
                clearCart()
                loadParkedOrders(context)
            }
        }
    }

    /**
     * Restores a parked order to the active cart.
     *
     * Previously this deleted the parked row and cleared the cart but never
     * actually decoded [ParkedOrderEntity.cartJson] back into [_cart] --
     * "resume" silently discarded the held items instead of restoring them.
     * Found while wiring Hold/Resume into app/ourea's UI for the first time
     * (nothing had called this path before, so the gap was never exercised).
     * A product that no longer exists in the current catalog (deleted/
     * deactivated since the order was parked) is skipped with a warning
     * rather than crashing the resume.
     */
    fun resumeOrder(context: Context, entity: ParkedOrderEntity) {
        val db = LocalDatabase.getInstance(context)
        // Something already in the cart is held first (a swap), never thrown away:
        // resuming B while A (itself resumed, so no longer held) was in the cart
        // used to lose A for good.
        val displaced = if (_cart.isNotEmpty()) currentCartAsParked(null) else null
        viewModelScope.launch(Dispatchers.IO) {
            displaced?.let { db.parkedOrderDao().insert(it) }
            val lines = try {
                Json.decodeFromString<List<ParkedCartLine>>(entity.cartJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode parked cart ${entity.id}: ${e.message}")
                emptyList()
            }
            db.parkedOrderDao().delete(entity)
            withContext(Dispatchers.Main) {
                clearCart()
                addLines(lines)
                orderNote = entity.orderNote.orEmpty()
                takeout = entity.takeout
                loadParkedOrders(context)
            }
        }
    }

    private fun addLines(lines: List<ParkedCartLine>) {
        lines.forEach { line ->
            val product = RetailRepository.catalog.value.find { it.id == line.productId }
            if (product != null) {
                val allOptions = product.modifierGroups.flatMap { it.options }
                val modifiers = line.modifierIds.mapNotNull { id -> allOptions.find { it.id == id } }
                repeat(line.quantity) { addItem(product, modifiers) }
                line.discount?.let { d ->
                    _cart.lastOrNull { it.product.id == product.id && it.selectedModifiers == modifiers }
                        ?.let { applyLineDiscount(it.id, d) }
                }
            } else {
                Log.w(TAG, "Cart line referenced unknown product ${line.productId} (catalog changed since?)")
            }
        }
    }

    // ---- The cart being rung up survives a restart (crash, update, power cut) ----

    private var cartPrefs: android.content.SharedPreferences? = null
    private var restoring = false

    /**
     * Brings back the cart the app had when it last stopped, and from then on
     * saves it on every change. Once per ViewModel; waits (up to 15 s) for the
     * catalog, which the lines are matched against.
     */
    fun restoreCart(context: Context) {
        if (cartPrefs != null) return
        val prefs = context.applicationContext.getSharedPreferences("retail_cart", Context.MODE_PRIVATE)
        cartPrefs = prefs
        val saved = prefs.getString("lines", null) ?: return
        viewModelScope.launch {
            val lines = try { Json.decodeFromString<List<ParkedCartLine>>(saved) } catch (e: Exception) { emptyList() }
            if (lines.isEmpty()) return@launch
            withTimeoutOrNull(15_000) { RetailRepository.catalog.first { it.isNotEmpty() } }
            if (_cart.isNotEmpty()) return@launch // already ringing up something new
            restoring = true
            addLines(lines)
            orderDiscount = prefs.getString("order_discount", null)?.let {
                runCatching { Json.decodeFromString<com.goldsky.ssp.payment.Discount>(it) }.getOrNull()
            }
            restoring = false
            saveCart()
        }
    }

    private fun saveCart() {
        if (restoring) return
        val prefs = cartPrefs ?: return
        val json = Json.encodeToString(
            _cart.map { ParkedCartLine(it.product.id, it.quantity, it.selectedModifiers.map { m -> m.id }, it.discount) }
        )
        prefs.edit().putString("lines", json)
            .putString("order_discount", orderDiscount?.let { Json.encodeToString(it) })
            .apply()
    }

    private fun currentCartAsParked(tableName: String?) = ParkedOrderEntity(
        tableName = tableName ?: "Quick Order",
        cartJson = Json.encodeToString(
            _cart.map { ParkedCartLine(it.product.id, it.quantity, it.selectedModifiers.map { m -> m.id }, it.discount) }
        ),
        subtotalCents = _totalCents.value,
        orderNote = orderNote.ifBlank { null },
        takeout = takeout
    )

    /** What a held order contains, e.g. "Soy Sauce x2, Tofu" -- every held order is "Quick Order" otherwise. */
    fun parkedSummary(entity: ParkedOrderEntity): String = try {
        Json.decodeFromString<List<ParkedCartLine>>(entity.cartJson).joinToString(", ") { line ->
            val name = RetailRepository.catalog.value.find { it.id == line.productId }?.name ?: "?"
            if (line.quantity > 1) "$name ×${line.quantity}" else name
        }
    } catch (e: Exception) { "" }

    /**
     * Adds an item to the cart using its barcode.
     */
    fun addByBarcode(context: Context, barcode: String) {
        val product = RetailRepository.getProductByBarcode(barcode)
        if (product != null) {
            addItem(product)
            FeedbackManager.success(context)
            _lastScan.value = ScanFeedback(barcode, product.name)
        } else {
            Log.w(TAG, "Unknown barcode scanned: $barcode")
            FeedbackManager.failure(context)
            _lastScan.value = ScanFeedback(barcode, null)
        }
    }

    fun addItem(product: Product, modifiers: List<ProductModifier> = emptyList()) {
        val existing = _cart.find { it.product.id == product.id && it.selectedModifiers == modifiers }
        if (existing != null) {
            // A new item, never the old one changed in place: an in-place ++ made
            // old == new, so the change could be missed by what watches the cart
            // (the row, the Ourea Eye sync).
            _cart[_cart.indexOf(existing)] = existing.copy(quantity = existing.quantity + 1)
        } else {
            _cart.add(CartItem(product = product, selectedModifiers = modifiers))
        }
        updateTotals()
    }

    fun removeItem(cartItemId: String) {
        val item = _cart.find { it.id == cartItemId }
        if (item != null) {
            if (item.quantity > 1) {
                val index = _cart.indexOf(item)
                _cart[index] = item.copy(quantity = item.quantity - 1)
            } else {
                _cart.remove(item)
            }
        }
        updateTotals()
    }

    // ---- Quick-service order details (Settings > Business type) ----

    /** Order note for the kitchen ("no green onion"), and dine-in vs take-out. Reset with the cart. */
    var orderNote by androidx.compose.runtime.mutableStateOf("")
    var takeout by androidx.compose.runtime.mutableStateOf(false)
    private var reservedNumber: Int? = null

    /**
     * The order as the kitchen sees it. The call number is taken once per
     * order, at checkout, and kept if the payment fails and is retried.
     */
    fun kitchenTicket(context: Context, staffName: String?): com.goldsky.ssp.model.KitchenTicket {
        val number = reservedNumber ?: com.goldsky.ssp.payment.OrderNumbers.next(context).also { reservedNumber = it }
        return com.goldsky.ssp.model.KitchenTicket(
            number = number,
            takeout = takeout,
            note = orderNote,
            lines = _cart.map { item -> com.goldsky.ssp.model.KitchenLine(item.product.name, item.quantity, item.selectedModifiers.map { it.name }) },
            staffName = staffName
        )
    }

    // ---- Discounts (Pricing: line first, then the order's, tax on what is left) ----

    /** The whole order's discount; a line's own is on its CartItem. */
    var orderDiscount by androidx.compose.runtime.mutableStateOf<com.goldsky.ssp.payment.Discount?>(null)
        private set

    fun applyOrderDiscount(discount: com.goldsky.ssp.payment.Discount?) {
        orderDiscount = discount
        updateTotals()
    }

    fun applyLineDiscount(cartItemId: String, discount: com.goldsky.ssp.payment.Discount?) {
        val i = _cart.indexOfFirst { it.id == cartItemId }
        if (i < 0) return
        _cart[i] = _cart[i].copy(discount = discount)
        updateTotals()
    }

    /** The cart priced: what each line costs after discounts, the discount, tax and total. */
    fun pricing(): com.goldsky.ssp.payment.Pricing.Result {
        val fallback = com.goldsky.ssp.payment.SalesTax.deviceRateBps()
        return com.goldsky.ssp.payment.Pricing.price(
            _cart.map { com.goldsky.ssp.payment.Pricing.Line(it.totalPriceCents, it.product.taxRateBps ?: fallback, it.discount) },
            orderDiscount
        )
    }

    fun clearCart() {
        orderNote = ""
        takeout = false
        orderDiscount = null
        reservedNumber = null
        _cart.clear()
        updateTotals()
        _lastScan.value = null
    }

    /**
     * Tax on the current cart, per product rate (Product.taxRateBps), with the
     * device's configured rate for products that aren't classified yet.
     * Computed at checkout, not cached, so a Settings rate change applies.
     */
    fun cartTaxCents(): Int = checkoutSale().taxCents

    /** The cart as a sale: lines (for transaction_items / receipt) plus totals. */
    fun checkoutSale(): com.goldsky.ssp.model.CheckoutSale {
        val fallback = com.goldsky.ssp.payment.SalesTax.deviceRateBps()
        val priced = pricing()
        val lines = _cart.mapIndexed { i, item ->
            com.goldsky.ssp.model.SaleLine(
                name = if (item.selectedModifiers.isEmpty()) item.product.name
                       else item.product.name + " (" + item.selectedModifiers.joinToString { it.name } + ")",
                quantity = item.quantity,
                unitPriceCents = item.unitPriceCents,
                taxRateBps = item.product.taxRateBps ?: fallback,
                productId = item.product.id,
                barcode = item.product.barcode,
                discountCents = priced.lines[i].discountCents,
                discountLabel = item.discount?.label
            )
        }
        val applied = _cart.mapIndexedNotNull { i, item ->
            item.discount?.let { com.goldsky.ssp.payment.AppliedDiscount.of(it, "LINE", priced.lines[i].lineDiscountCents, lineNo = i + 1) }
        } + listOfNotNull(orderDiscount?.let { com.goldsky.ssp.payment.AppliedDiscount.of(it, "ORDER", priced.orderDiscountCents) })
        return com.goldsky.ssp.model.CheckoutSale(
            subtotalCents = lines.sumOf { it.lineTotalCents },
            taxCents = com.goldsky.ssp.payment.SalesTax.cents(lines.map { it.lineTotalCents to it.taxRateBps }),
            lines = lines,
            discountCents = priced.discountCents,
            discounts = applied.filter { it.amount_cents > 0 }
        )
    }

    private fun updateTotals() {
        // After discounts, before tax.
        _totalCents.value = pricing().netCents
        _itemCount.value = _cart.sumOf { it.quantity }
        saveCart()
    }
}

