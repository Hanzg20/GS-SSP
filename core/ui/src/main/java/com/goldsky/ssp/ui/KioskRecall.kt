package com.goldsky.ssp.ui

import android.app.Activity
import android.app.Application
import android.util.Log
import com.goldsky.ssp.payment.KioskFront
import kotlinx.coroutines.delay

/**
 * Remote BRING_TO_FRONT from Cael: brings the kiosk back over whatever covers it (OPC's
 * page after a reboot, seen on 253 2026-10-25: nobody on site could leave it). Same
 * starts as [BootFrontGuard], but any time, not only in the first minutes after boot.
 * Only the HOME holder acts (on a two-app terminal the other app leaves the command
 * alone), and the caller only asks while nobody is paying.
 */
object KioskRecall {
    private const val TAG = "KioskRecall"
    private const val TRIES = 4
    private const val WAIT_MS = 3_000L

    /** Whether this app should take the command: it is the one on the HOME role. */
    fun handles(app: Application): Boolean = BootFrontGuard.holdsHome(app)

    /** Tries up to [TRIES] times; returns whether the kiosk ended up in front, and what happened. */
    suspend fun bringBack(app: Application, cls: Class<out Activity>): Pair<Boolean, String> {
        if (KioskFront.inFront()) return true to "Already in front"
        val steps = StringBuilder()
        for (i in 1..TRIES) {
            val viaHome = i % 2 == 0
            val sent = BootFrontGuard.relaunch(app, cls, viaHome)
            delay(WAIT_MS)
            val front = KioskFront.inFront()
            if (steps.isNotEmpty()) steps.append(", ")
            steps.append("#$i ${if (viaHome) "home" else "kiosk"}${if (sent) "" else " refused"}: ${if (front) "in front" else "covered"}")
            if (front) {
                Log.i(TAG, "Back in front: $steps")
                return true to "Back in front ($steps)"
            }
        }
        Log.w(TAG, "Still covered: $steps")
        return false to "Still covered after $TRIES tries ($steps)"
    }
}
