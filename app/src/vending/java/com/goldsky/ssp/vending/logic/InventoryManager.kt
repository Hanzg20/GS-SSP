package com.goldsky.ssp.vending.logic

import android.util.Log

/**
 * Manages local inventory for Vending Machine slots.
 * Simplified singleton for development; in production, this would use a secure EncryptedPrefs or Room.
 */
object InventoryManager {
    private const val TAG = "InventoryManager"
    
    // In-memory stock map: Slot ID -> Remaining Count
    private val stockMap = mutableMapOf<String, Int>(
        "A1" to 15,
        "A2" to 4,
        "A3" to 8,
        "B1" to 20,
        "B2" to 0
    )

    fun getStock(slot: String): Int {
        return stockMap[slot] ?: 0
    }

    fun isSoldOut(slot: String): Boolean {
        return getStock(slot) <= 0
    }

    /**
     * Decrements stock for a given slot after a successful physical dispense.
     */
    fun decrement(slot: String) {
        val current = getStock(slot)
        if (current > 0) {
            stockMap[slot] = current - 1
            Log.i(TAG, "Stock decremented for $slot: ${current - 1} remaining")
        } else {
            Log.w(TAG, "Attempted to decrement empty stock for $slot")
        }
    }

    fun setStock(slot: String, count: Int) {
        stockMap[slot] = count
        Log.i(TAG, "Stock updated for $slot: $count")
    }

    fun getAllStock(): Map<String, Int> {
        return stockMap.toMap()
    }
}
