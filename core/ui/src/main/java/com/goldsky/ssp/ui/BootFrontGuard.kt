package com.goldsky.ssp.ui

import android.app.Activity
import android.app.Application
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Brings the kiosk back to the front when something else covers it right
 * after boot. Since OPC 2.17.3 (TMS update 2026-10-07) WizarPOS OPC opens its
 * own MainActivity ~9 s after BOOT_COMPLETED, on top of our HOME screen, and
 * the terminal sat on the OPC page until someone tapped through it.
 *
 * Only acts when all hold: the device booted less than [BOOT_WINDOW_MS] ago,
 * no customer has touched our screen yet (a payment always starts with a
 * touch, so PAYWizard's card screen is never pushed away), this app is the
 * HOME holder (on a two-app terminal only the active app), and at most
 * [MAX_RELAUNCHES] times.
 */
object BootFrontGuard {
    private const val TAG = "BootFrontGuard"
    private const val BOOT_WINDOW_MS = 3 * 60_000L
    private const val RELAUNCH_DELAY_MS = 1_500L
    private const val MAX_RELAUNCHES = 3

    private var installed = false
    private var touched = false
    private var started = 0
    private var relaunches = 0
    private val handler = Handler(Looper.getMainLooper())

    /** Call from the kiosk activity's onCreate. */
    fun install(activity: Activity) {
        if (installed) return
        installed = true
        val app = activity.application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) {
                started--
                if (started > 0) return
                // Whole app went to the background.
                handler.postDelayed({ maybeRelaunch(app, a.javaClass) }, RELAUNCH_DELAY_MS)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Call on any touch on our screens. */
    fun onTouch() { touched = true }

    private fun maybeRelaunch(app: Application, cls: Class<out Activity>) {
        if (started > 0 || touched || relaunches >= MAX_RELAUNCHES) return
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return
        if (!holdsHome(app)) return
        relaunches++
        Log.w(TAG, "Covered by another app ${SystemClock.elapsedRealtime() / 1000}s after boot; back to front (#$relaunches)")
        runCatching {
            app.startActivity(Intent(app, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { Log.w(TAG, "Relaunch refused: ${it.message}") }
    }

    private fun holdsHome(app: Application): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return runCatching { app.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
    }
}
