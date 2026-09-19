package com.goldsky.ssp.payment

import com.goldsky.ssp.model.PlaylistEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.*

/**
 * Evaluates local targeting rules for advertisements.
 */
object AdTargetingEvaluator {

    fun isAdActive(entry: PlaylistEntry): Boolean {
        val rules = entry.targeting_rules as? JsonObject ?: return true
        
        // 1. Time Check
        val startHour = (rules["start_hour"] as? JsonPrimitive)?.content?.toIntOrNull()
        val endHour = (rules["end_hour"] as? JsonPrimitive)?.content?.toIntOrNull()
        
        if (startHour != null && endHour != null) {
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (currentHour < startHour || currentHour >= endHour) return false
        }

        // 2. Day Check
        val activeDays = (rules["active_days"] as? JsonPrimitive)?.content?.split(",") ?: emptyList()
        if (activeDays.isNotEmpty()) {
            val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).toString() // 1 (Sun) to 7 (Sat)
            if (currentDay !in activeDays) return false
        }

        return true
    }

    fun getPriority(entry: PlaylistEntry): Int {
        val rules = entry.targeting_rules as? JsonObject ?: return 0
        return (rules["priority"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    }
}
