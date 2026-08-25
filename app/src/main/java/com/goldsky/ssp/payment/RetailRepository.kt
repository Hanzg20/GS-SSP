package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.db.ProductEntity
import com.goldsky.ssp.model.ModifierGroup
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.model.ProductModifier
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the retail product catalog and barcode lookups.
 * Implementation: Local Cache (Room) + Background Sync (Supabase).
 */
object RetailRepository {
    private const val TAG = "RetailRepository"
    
    private val _catalog = MutableStateFlow<List<Product>>(emptyList())
    val catalog: StateFlow<List<Product>> = _catalog.asStateFlow()

    fun init(context: Context) {
        val db = LocalDatabase.getInstance(context)
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Load from Disk immediately
            val entities = db.productDao().getAll()
            if (entities.isNotEmpty()) {
                _catalog.value = entities.map { it.toModel() }
                Log.d(TAG, "Loaded ${_catalog.value.size} products from local DB")
            }

            // 2. Trigger Background Sync
            syncWithCloud(context)
        }
    }

    suspend fun syncWithCloud(context: Context) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting cloud product sync from Supabase...")
            val region = DeviceRepository.getRegion()
            
            val response = SupabaseClientProvider.client.postgrest["products"]
                .select {
                    filter {
                        eq("vertical_type", "RETAIL")
                        eq("is_active", true)
                        // In a real schema, we'd have a region or currency column.
                        // For now, we'll assume org-level products are filtered by region.
                        // eq("region", region) 
                    }
                }
            
            val products = response.decodeList<Product>()
            
            if (products.isNotEmpty()) {
                val db = LocalDatabase.getInstance(context)
                val entities = products.map { it.toEntity() }
                
                // Nuclear sync: clear and replace to ensure local matches cloud exactly
                db.runInTransaction {
                    // For production, we'd do a more graceful upsert/delete-orphans
                    // but for the demo, this ensures clean data.
                    // Note: ProductDao needs a deleteAll if we want perfect mirroring.
                    // For now, insertAll(REPLACE) handles existing items.
                    runBlocking { db.productDao().insertAll(entities) }
                }
                
                _catalog.value = products
                Log.i(TAG, "Sync complete: ${products.size} products from Supabase cached.")
            } else {
                Log.w(TAG, "Sync returned empty product list from cloud.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloud sync failed: ${e.message}. Using local cache.")
            if (_catalog.value.isEmpty()) seedMockData(LocalDatabase.getInstance(context))
        }
    }

    private fun seedMockData(db: LocalDatabase) {
        val coffeeModifiers = listOf(
            ModifierGroup(
                id = "size",
                name = "Size",
                options = listOf(
                    ProductModifier("s1", "Regular", 0),
                    ProductModifier("s2", "Large", 150)
                )
            ),
            ModifierGroup(
                id = "add",
                name = "Add-ons",
                options = listOf(
                    ProductModifier("a1", "Extra Shot", 50),
                    ProductModifier("a2", "Oat Milk", 80)
                )
            )
        )

        val mocks = listOf(
            ProductEntity("1", "Coca Cola 500ml", 250, "6901234567890", "DRINK"),
            ProductEntity("2", "Lays Classic", 150, "6901111111111", "SNACK"),
            // Coffee with modifiers
            ProductEntity("3", "Coffee Latte", 400, "6902222222222", "DRINK")
        )
        
        // Note: Real implementation would store modifier groups in ProductEntity attributes JSONB
        // For the demo, I'll update the memory _catalog directly after seeding basic entities
        CoroutineScope(Dispatchers.IO).launch {
            db.productDao().insertAll(mocks)
            val updatedMocks = mocks.map { 
                val model = it.toModel()
                if (model.id == "3") model.copy(modifier_groups = coffeeModifiers) else model
            }
            _catalog.value = updatedMocks
        }
    }

    private fun ProductEntity.toModel() = Product(
        id = id, name = name, price_cents = priceCents, vertical_type = "RETAIL", barcode = barcode
    )

    private fun Product.toEntity() = ProductEntity(
        id = id, name = name, priceCents = price_cents, barcode = barcode, category = "UNSET"
    )

    fun getProductByBarcode(barcode: String): Product? {
        return _catalog.value.find { it.barcode == barcode }
    }
}
