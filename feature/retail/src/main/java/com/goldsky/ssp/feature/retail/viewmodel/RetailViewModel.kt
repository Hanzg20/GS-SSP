package com.goldsky.ssp.feature.retail.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
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
private data class ParkedCartLine(val productId: String, val quantity: Int, val modifierIds: List<String> = emptyList())

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
                loadParkedOrders(context)
            }
        }
    }

    private fun addLines(lines: List<ParkedCartLine>) {
        lines.forEach { line ->
            val product = RetailRepository.catalog.value.find { it.id == line.productId }
            if (product != null) {
                val allOptions = product.modifier_groups?.flatMap { it.options } ?: emptyList()
                val modifiers = line.modifierIds.mapNotNull { id -> allOptions.find { it.id == id } }
                repeat(line.quantity) { addItem(product, modifiers) }
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
            restoring = false
            saveCart()
        }
    }

    private fun saveCart() {
        if (restoring) return
        val prefs = cartPrefs ?: return
        val json = Json.encodeToString(
            _cart.map { ParkedCartLine(it.product.id, it.quantity, it.selectedModifiers.map { m -> m.id }) }
        )
        prefs.edit().putString("lines", json).apply()
    }

    private fun currentCartAsParked(tableName: String?) = ParkedOrderEntity(
        tableName = tableName ?: "Quick Order",
        cartJson = Json.encodeToString(
            _cart.map { ParkedCartLine(it.product.id, it.quantity, it.selectedModifiers.map { m -> m.id }) }
        ),
        subtotalCents = _totalCents.value
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

    fun clearCart() {
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
        val lines = _cart.map { item ->
            com.goldsky.ssp.model.SaleLine(
                name = if (item.selectedModifiers.isEmpty()) item.product.name
                       else item.product.name + " (" + item.selectedModifiers.joinToString { it.name } + ")",
                quantity = item.quantity,
                unitPriceCents = item.unitPriceCents,
                taxRateBps = item.product.taxRateBps ?: fallback,
                productId = item.product.id,
                barcode = item.product.barcode
            )
        }
        return com.goldsky.ssp.model.CheckoutSale(
            subtotalCents = lines.sumOf { it.lineTotalCents },
            taxCents = com.goldsky.ssp.payment.SalesTax.cents(lines.map { it.lineTotalCents to it.taxRateBps }),
            lines = lines
        )
    }

    private fun updateTotals() {
        _totalCents.value = _cart.sumOf { it.totalPriceCents }
        _itemCount.value = _cart.sumOf { it.quantity }
        saveCart()
    }
}

