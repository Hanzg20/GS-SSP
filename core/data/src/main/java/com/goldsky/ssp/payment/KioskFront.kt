package com.goldsky.ssp.payment

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager

/**
 * Whether this kiosk app's screen is in front, for the heartbeat
 * (`heartbeats.kiosk_in_front` -> `devices.kiosk_in_front`, docs/migrations/2026-10-21_kiosk_in_front.sql).
 * Only kiosk apps report it: they call [watch] (BootFrontGuard.install does);
 * everything else sends NULL.
 */
object KioskFront {
    @Volatile var watching = false
        private set

    fun watch() { watching = true }

    /** One of this process's activities is on screen -- asked from the system, not counted. */
    fun inFront(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * What the heartbeat carries: null for a non-kiosk app, and while the screen is
     * off (nothing is in front then; the last reported state stays).
     */
    fun forHeartbeat(context: Context): Boolean? {
        if (!watching) return null
        val screenOn = runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(true)
        return if (screenOn) inFront() else null
    }
}
