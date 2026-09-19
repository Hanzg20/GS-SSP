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

    private val _lastScannedProductName = MutableStateFlow<String?>(null)
    val lastScannedProductName: StateFlow<String?> = _lastScannedProductName.asStateFlow()

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
        val cartJson = Json.encodeToString(
            _cart.map { ParkedCartLine(it.product.id, it.quantity, it.selectedModifiers.map { m -> m.id }) }
        )

        val entity = ParkedOrderEntity(
            tableName = tableName ?: "Quick Order",
            cartJson = cartJson,
            subtotalCents = _totalCents.value
        )
        
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
        viewModelScope.launch(Dispatchers.IO) {
            val lines = try {
                Json.decodeFromString<List<ParkedCartLine>>(entity.cartJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode parked cart ${entity.id}: ${e.message}")
                emptyList()
            }
            db.parkedOrderDao().delete(entity)
            withContext(Dispatchers.Main) {
                clearCart()
                lines.forEach { line ->
                    val product = RetailRepository.catalog.value.find { it.id == line.productId }
                    if (product != null) {
                        val allOptions = product.modifier_groups?.flatMap { it.options } ?: emptyList()
                        val modifiers = line.modifierIds.mapNotNull { id -> allOptions.find { it.id == id } }
                        repeat(line.quantity) { addItem(product, modifiers) }
                    } else {
                        Log.w(TAG, "Parked order referenced unknown product ${line.productId} (catalog changed since it was held?)")
                    }
                }
                loadParkedOrders(context)
            }
        }
    }

    /**
     * Adds an item to the cart using its barcode.
     */
    fun addByBarcode(context: Context, barcode: String) {
        val product = RetailRepository.getProductByBarcode(barcode)
        if (product != null) {
            addItem(product)
            FeedbackManager.success(context)
            _lastScannedProductName.value = product.name
        } else {
            Log.w(TAG, "Unknown barcode scanned: $barcode")
            FeedbackManager.failure(context)
        }
    }

    fun addItem(product: Product, modifiers: List<ProductModifier> = emptyList()) {
        val existing = _cart.find { it.product.id == product.id && it.selectedModifiers == modifiers }
        if (existing != null) {
            existing.quantity++
            val index = _cart.indexOf(existing)
            _cart[index] = existing.copy(quantity = existing.quantity)
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
        _lastScannedProductName.value = null
    }

    private fun updateTotals() {
        _totalCents.value = _cart.sumOf { it.totalPriceCents }
        _itemCount.value = _cart.sumOf { it.quantity }
    }
}

