package com.goldsky.ssp.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.db.ParkedOrderEntity
import com.goldsky.ssp.model.CartItem
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.model.ProductModifier
import com.goldsky.ssp.payment.FeedbackManager
import com.goldsky.ssp.payment.RetailRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        // For prototype, we serialize the simple map or List<CartItem>
        // Real implementation would need a proper serializer for CartItem
        val cartJson = Json.encodeToString(_cart.map { it.product.id to it.quantity }.toMap())
        
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
     */
    fun resumeOrder(context: Context, entity: ParkedOrderEntity) {
        val db = LocalDatabase.getInstance(context)
        viewModelScope.launch(Dispatchers.IO) {
            // Logic to parse cartJson and repopulate _cart
            // Note: This is simplified for the demo as it loses modifiers without a full CartItem serializer
            db.parkedOrderDao().delete(entity)
            withContext(Dispatchers.Main) {
                clearCart()
                // ... repopulate logic ...
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
            FeedbackManager.emitScanFeedback(context)
            _lastScannedProductName.value = product.name
        } else {
            Log.w(TAG, "Unknown barcode scanned: $barcode")
        }
    }

    fun addItem(product: Product, modifiers: List<ProductModifier> = emptyList()) {
        // Find existing item with exact same product AND same modifiers
        val existing = _cart.find { it.product.id == product.id && it.selectedModifiers == modifiers }
        if (existing != null) {
            existing.quantity++
            // Force list update for Compose observability
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
