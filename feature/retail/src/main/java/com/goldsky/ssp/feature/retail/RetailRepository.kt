package com.goldsky.ssp.feature.retail

import android.content.Context
import android.util.Log
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.db.ProductEntity
import com.goldsky.ssp.model.ModifierGroup
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.model.ProductModifier
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
        val db = LocalDatabase.getInstance(context)
        CoroutineScope(Dispatchers.IO).launch {
            val entities = db.productDao().getAll()
            if (entities.isNotEmpty()) {
                _catalog.value = entities.map { it.toModel() }
                Log.d(TAG, "Loaded ${_catalog.value.size} products from local DB")
            }
            syncWithCloud(context)
        }
    }

    suspend fun syncWithCloud(context: Context) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting cloud product sync from Supabase...")
            
            val response = SupabaseClientProvider.client.postgrest["products"]
                .select {
                    filter {
                        eq("vertical_type", "RETAIL")
                        eq("is_active", true)
                    }
                }
            
            val products = response.decodeList<Product>()

            // Unconditional, not gated on isNotEmpty(): the old gate meant a product that
            // became inactive/deleted in the cloud (or a catalog fully emptied out) was
            // never purged from local_products/_catalog -- it would keep showing up in the
            // POS UI forever since insertAll only upserts by id, it never deletes. deleteAll
            // + insertAll inside the same transaction makes this sync a real replace, and
            // productDao's calls aren't suspend so no runBlocking is needed to call them from
            // inside runInTransaction's synchronous lambda.
            val db = LocalDatabase.getInstance(context)
            val entities = products.map { it.toEntity() }

            db.runInTransaction {
                db.productDao().deleteAll()
                db.productDao().insertAll(entities)
            }

            _catalog.value = products
            Log.i(TAG, "Sync complete: ${products.size} products from Supabase cached.")
        } catch (e: Exception) {
            Log.e(TAG, "Cloud sync failed: ${e.message}")
        }
    }

    /**
     * Round-trips category + stock through attributes JSONB (see
     * Product.category/stockQty's doc comments) so the local-DB-first load
     * on cold start shows the same grouping/stock levels the cloud sync
     * will confirm moments later, instead of briefly dropping them.
     */
    private fun ProductEntity.toModel() = Product(
        id = id, name = name, price_cents = priceCents, vertical_type = "RETAIL", barcode = barcode,
        attributes = if (category != "UNSET" || stockQty != null) buildJsonObject {
            if (category != "UNSET") put("category", JsonPrimitive(category))
            if (stockQty != null) put("stock_qty", JsonPrimitive(stockQty))
        } else null
    )

    private fun Product.toEntity() = ProductEntity(
        id = id, name = name, priceCents = price_cents, barcode = barcode, category = category ?: "UNSET",
        stockQty = stockQty
    )

    fun getProductByBarcode(barcode: String): Product? {
        return _catalog.value.find { it.barcode == barcode }
    }
}

