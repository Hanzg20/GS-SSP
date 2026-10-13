package com.goldsky.ssp.payment

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quick-service call numbers ("取餐号"): 1, 2, 3... per terminal, back to 1
 * every day, wrapping after 999 so it always fits a 3-digit call board.
 */
object OrderNumbers {
    private const val PREFS = "order_numbers"

    @Synchronized
    fun next(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val last = if (prefs.getString("day", null) == today) prefs.getInt("last", 0) else 0
        val n = if (last >= 999) 1 else last + 1
        prefs.edit().putString("day", today).putInt("last", n).apply()
        return n
    }
}
