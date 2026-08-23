package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.db.ProductEntity
import com.goldsky.ssp.model.Product
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the retail product catalog and barcode lookups.
 */
object RetailRepository {
    private const val TAG = "RetailRepository"
    
    private val _catalog = MutableStateFlow<List<Product>>(emptyList())
    val catalog: StateFlow<List<Product>> = _catalog.asStateFlow()

    fun init(context: Context) {
        // Hydrate catalog from Room DB
        val db = LocalDatabase.getInstance(context)
        CoroutineScope(Dispatchers.IO).launch {
            val entities = db.productDao().getAll()
            if (entities.isEmpty()) {
                // Seed mock data if empty
                seedMockData(db)
            } else {
                _catalog.value = entities.map { it.toModel() }
            }
        }
    }

    private suspend fun seedMockData(db: LocalDatabase) {
        val mocks = listOf(
            ProductEntity("1", "Coca Cola 500ml", 250, "6901234567890", "DRINK"),
            ProductEntity("2", "Lays Classic", 150, "6901111111111", "SNACK"),
            ProductEntity("3", "Coffee Latte", 400, "6902222222222", "DRINK"),
            ProductEntity("4", "Bottled Water", 100, "6903333333333", "DRINK")
        )
        db.productDao().insertAll(mocks)
        _catalog.value = mocks.map { it.toModel() }
    }

    private fun ProductEntity.toModel() = Product(
        id = id, name = name, price_cents = priceCents, vertical_type = "RETAIL", barcode = barcode
    )

    fun getProductByBarcode(barcode: String): Product? {
        return _catalog.value.find { it.barcode == barcode }
    }

    fun updateCatalog(products: List<Product>) {
        // Ready for cloud sync
    }
}
