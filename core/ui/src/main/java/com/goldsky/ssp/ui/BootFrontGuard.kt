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
import android.provider.Settings
import android.util.Log
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.DiagnosticManager

/**
 * Brings the kiosk back to the front when something else covers it right
 * after boot. Since OPC 2.17.3 (TMS update 2026-10-07) WizarPOS OPC opens its
 * own MainActivity ~9 s after BOOT_COMPLETED, on top of our HOME screen, and
 * the terminal sat on the OPC page until someone tapped through it. (WizarPOS,
 * 2026-10-20: in socket mode -- ours -- OPC starts in the foreground unless
 * configured otherwise.)
 *
 * Only acts when all hold: the device booted less than [BOOT_WINDOW_MS] ago,
 * no customer has touched our screen yet (a payment always starts with a
 * touch, so PAYWizard's card screen is never pushed away), this app is the
 * HOME holder (on a two-app terminal only the active app), and at most
 * [MAX_RELAUNCHES] times.
 *
 * Reported (KIOSK_COVERED_AT_BOOT), the first time per boot: what it did and why --
 * 253 and 254 kept the OPC page after a reboot with this guard installed while 255
 * never did, and they can't be reached over adb, so the reason has to come back from
 * the terminal: who holds HOME, whether the app may start activities from the
 * background (SYSTEM_ALERT_WINDOW -- what let it on 255), and whether it really came
 * back to the front.
 */
object BootFrontGuard {
    private const val TAG = "BootFrontGuard"
    private const val BOOT_WINDOW_MS = 3 * 60_000L
    private const val RELAUNCH_DELAY_MS = 1_500L
    private const val VERIFY_MS = 2_000L
    private const val MAX_RELAUNCHES = 3

    private var installed = false
    private var touched = false
    private var started = 0
    private var relaunches = 0
    private var reported = false
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
        if (started > 0) return
        val uptime = SystemClock.elapsedRealtime() / 1000
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return
        when {
            touched -> { report(app, uptime, "not brought back: the screen was touched (a sale may be under way)"); return }
            relaunches >= MAX_RELAUNCHES -> { report(app, uptime, "not brought back: already brought back $MAX_RELAUNCHES times"); return }
            !holdsHome(app) -> { report(app, uptime, "not brought back: this app isn't the default HOME"); return }
        }
        relaunches++
        Log.w(TAG, "Covered by another app ${uptime}s after boot; back to front (#$relaunches)")
        val sent = runCatching {
            app.startActivity(Intent(app, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { Log.w(TAG, "Relaunch refused: ${it.message}") }.isSuccess
        // Android can drop a background start without an error: check it really came back.
        handler.postDelayed({
            report(app, uptime, if (!sent) "relaunch threw" else if (started > 0) "brought back (#$relaunches)" else "relaunch sent but still covered (blocked by Android?)")
        }, VERIFY_MS)
    }

    /** One report per boot, with what decides the outcome. */
    private fun report(app: Application, uptime: Long, what: String) {
        if (reported) return
        reported = true
        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(app) else true
        val trace = "kiosk covered ${uptime}s after boot: $what; holdsHomeRole=${holdsHome(app)}, overlayPermission=$overlay"
        Log.w(TAG, trace)
        runCatching { DiagnosticManager.reportError(DeviceRepository.getPersistedDeviceSn().orEmpty(), "KIOSK_COVERED_AT_BOOT", severity = "WARNING", trace = trace) }
    }

    private fun holdsHome(app: Application): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return runCatching { app.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
    }
}
