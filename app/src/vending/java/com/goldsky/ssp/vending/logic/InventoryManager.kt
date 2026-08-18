package com.goldsky.ssp.vending.logic

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages local inventory for Vending Machine slots.
 * Features JSON file persistence for resilience against power failure.
 */
object InventoryManager {
    private const val TAG = "InventoryManager"
    private const val FILENAME = "inventory_v2.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    
    // In-memory stock map: Slot ID -> Remaining Count
    private var stockMap = mutableMapOf<String, Int>()

    /**
     * Initializes inventory from local storage or defaults.
     */
    fun init(context: Context) {
        val file = File(context.filesDir, FILENAME)
        if (file.exists()) {
            try {
                val content = file.readText()
                stockMap = json.decodeFromString<Map<String, Int>>(content).toMutableMap()
                Log.i(TAG, "Inventory loaded from file: $stockMap")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load inventory: ${e.message}")
                loadDefaults()
            }
        } else {
            loadDefaults()
            save(context)
        }
    }

    private fun loadDefaults() {
        stockMap = mutableMapOf(
            "A1" to 15,
            "A2" to 4,
            "A3" to 8,
            "B1" to 20,
            "B2" to 0
        )
    }

    private fun save(context: Context) {
        try {
            val content = json.encodeToString(stockMap)
            File(context.filesDir, FILENAME).writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save inventory: ${e.message}")
        }
    }

    fun getStock(slot: String): Int {
        return stockMap[slot] ?: 0
    }

    fun isSoldOut(slot: String): Boolean {
        return getStock(slot) <= 0
    }

    /**
     * Decrements stock for a given slot after a successful physical dispense.
     */
    fun decrement(context: Context, slot: String) {
        val current = getStock(slot)
        if (current > 0) {
            stockMap[slot] = current - 1
            save(context)
            Log.i(TAG, "Stock decremented for $slot: ${current - 1} remaining")
        } else {
            Log.w(TAG, "Attempted to decrement empty stock for $slot")
        }
    }

    fun setStock(context: Context, slot: String, count: Int) {
        stockMap[slot] = count
        save(context)
        Log.i(TAG, "Stock updated for $slot: $count")
    }

    fun getAllStock(): Map<String, Int> {
        return stockMap.toMap()
    }
}
