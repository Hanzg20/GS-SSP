package com.goldsky.ssp.feature.timer

import android.content.Context

/**
 * Persists the in-flight paid session so a crash, watchdog restart or reboot
 * mid-countdown can (a) force the output off and (b) give the customer the
 * time they already paid for. Wall-clock based on purpose: elapsedRealtime
 * resets on reboot.
 */
class SessionStore(context: Context) {
    data class Active(val ecrRefNum: String, val productId: String, val endAtWallMs: Long, val totalMs: Long)

    private val prefs = context.applicationContext.getSharedPreferences("timer_session", Context.MODE_PRIVATE)

    fun save(s: Active) {
        prefs.edit()
            .putString(KEY_REF, s.ecrRefNum)
            .putString(KEY_PRODUCT, s.productId)
            .putLong(KEY_END, s.endAtWallMs)
            .putLong(KEY_TOTAL, s.totalMs)
            .commit() // synchronous: this must be on disk before the output turns on
    }

    fun load(): Active? {
        val ref = prefs.getString(KEY_REF, null) ?: return null
        return Active(
            ref,
            prefs.getString(KEY_PRODUCT, "") ?: "",
            prefs.getLong(KEY_END, 0L),
            prefs.getLong(KEY_TOTAL, 0L),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_REF = "ecr_ref_num"
        const val KEY_PRODUCT = "product_id"
        const val KEY_END = "end_at_wall_ms"
        const val KEY_TOTAL = "total_ms"
    }
}
