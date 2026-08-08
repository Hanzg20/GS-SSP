package com.goldsky.carwash.payment

import android.util.Log
import com.goldsky.carwash.model.PlaylistEntry
import kotlinx.serialization.json.*
import java.util.Calendar

/**
 * Logic to evaluate if a playlist entry is active based on its JSON targeting rules.
 */
object AdTargetingEvaluator {
    private const val TAG = "AdTargeting"

    fun isAdActive(entry: PlaylistEntry): Boolean {
        val rules = entry.targeting_rules?.jsonObject ?: return true // No rules = always active
        
        try {
            val now = Calendar.getInstance()
            
            // 1. Weekly rule (days)
            rules["days"]?.jsonArray?.let { days ->
                val dayOfWeek = now.get(Calendar.DAY_OF_WEEK) // Sunday=1, Monday=2...
                val activeDays = days.mapNotNull { it.jsonPrimitive.intOrNull }
                if (dayOfWeek !in activeDays) return false
            }

            // 2. Hourly rule (start/end)
            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val startHour = rules["start_hour"]?.jsonPrimitive?.intOrNull ?: 0
            val endHour = rules["end_hour"]?.jsonPrimitive?.intOrNull ?: 24
            
            if (currentHour < startHour || currentHour >= endHour) return false

            // 3. Specific Date range (optional, for seasonal promos)
            // rules["start_date"] / rules["end_date"] could be added here

        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating rules for ad ${entry.ad_id}: ${e.message}")
            return true // Fallback to showing if rules are malformed
        }

        return true
    }

    /**
     * Extracts priority from rules. Default is 0.
     */
    fun getPriority(entry: PlaylistEntry): Int {
        return entry.targeting_rules?.jsonObject?.get("priority")?.jsonPrimitive?.intOrNull ?: 0
    }
}
