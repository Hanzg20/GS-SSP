package com.goldsky.ssp.payment

import android.content.Context

/** Settings: whether 小海狸 moves on this terminal (feature/apex BeaverMascot). On by default. */
object ApexMascotPrefs {
    private const val PREFS = "apex"
    private const val KEY = "mascot_animated"

    fun animated(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setAnimated(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
