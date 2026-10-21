package com.goldsky.ssp.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
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
 * Polls whether this app is in the foreground (asked from the system, not counted
 * from activity callbacks: a count was thrown off by the splash starting before the
 * guard was installed, and on 253 and 255 the guard then never acted -- 2026-10-21).
 * Polling also catches OPC coming to the front again after we were back.
 *
 * Only acts when all hold: the device booted less than [BOOT_WINDOW_MS] ago,
 * no customer has touched our screen yet (a payment always starts with a
 * touch, so PAYWizard's card screen is never pushed away), this app is the
 * HOME holder (on a two-app terminal only the active app), and at most
 * [MAX_RELAUNCHES] times. Starting an activity from the background is allowed
 * for the HOME app (BAL_ALLOW_ALLOWLISTED_COMPONENT, seen on 255).
 *
 * Reported once per boot (KIOSK_COVERED_AT_BOOT) with the whole timeline, when the
 * kiosk has stayed in front for [SETTLED_MS] or the guard gives up.
 */
object BootFrontGuard {
    private const val TAG = "BootFrontGuard"
    private const val BOOT_WINDOW_MS = 5 * 60_000L
    private const val POLL_MS = 3_000L
    private const val SETTLED_MS = 30_000L
    private const val MAX_RELAUNCHES = 6

    private var installed = false
    private var touched = false
    private var relaunches = 0
    private var reported = false
    private var frontSince = 0L
    private val timeline = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())

    /** Call from the kiosk activity's onCreate. */
    fun install(activity: Activity) {
        if (installed) return
        installed = true
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return
        val app = activity.application
        val cls = activity.javaClass
        handler.postDelayed(object : Runnable {
            override fun run() { if (poll(app, cls)) handler.postDelayed(this, POLL_MS) }
        }, POLL_MS)
    }

    /** Call on any touch on our screens. */
    fun onTouch() { touched = true }

    /** One check; false when the guard is done for this boot. */
    private fun poll(app: Application, cls: Class<out Activity>): Boolean {
        val now = SystemClock.elapsedRealtime()
        val uptime = now / 1000
        if (inFront()) {
            if (frontSince == 0L) {
                frontSince = now
                if (timeline.isNotEmpty()) note("${uptime}s in front")
            }
            // Back and stayed back: report what it took.
            if (timeline.isNotEmpty() && now - frontSince >= SETTLED_MS) { report(app, "back in front"); return false }
            return now < BOOT_WINDOW_MS
        }
        if (frontSince != 0L || timeline.isEmpty()) note("${uptime}s covered")
        frontSince = 0L
        when {
            now > BOOT_WINDOW_MS -> { report(app, "boot window over, still covered"); return false }
            touched -> { report(app, "not brought back: the screen was touched (a sale may be under way)"); return false }
            relaunches >= MAX_RELAUNCHES -> { report(app, "gave up after $MAX_RELAUNCHES tries, still covered"); return false }
            !holdsHome(app) -> { report(app, "not brought back: this app isn't the default HOME"); return false }
        }
        relaunches++
        // Every other try through HOME: lands on this app's HOME entry, which opens the kiosk.
        val viaHome = relaunches % 2 == 0
        val intent = if (viaHome) Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)
        else Intent(app, cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val sent = runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "Relaunch refused: ${it.message}") }.isSuccess
        note("#$relaunches ${if (viaHome) "home" else "kiosk"}${if (sent) "" else " refused"}")
        Log.w(TAG, "Covered ${uptime}s after boot; back to front (#$relaunches)")
        return true
    }

    /** Whether one of this app's activities is on screen -- asked from the system. */
    private fun inFront(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun note(s: String) { if (timeline.isNotEmpty()) timeline.append(", "); timeline.append(s) }

    /** One report per boot: the outcome, the timeline, and what decides it. */
    private fun report(app: Application, outcome: String) {
        if (reported) return
        reported = true
        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(app) else true
        val trace = "kiosk covered after boot: $outcome; $timeline; holdsHomeRole=${holdsHome(app)}, overlayPermission=$overlay"
        Log.w(TAG, trace)
        runCatching { DiagnosticManager.reportError(DeviceRepository.getPersistedDeviceSn().orEmpty(), "KIOSK_COVERED_AT_BOOT", severity = "WARNING", trace = trace) }
    }

    private fun holdsHome(app: Application): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return runCatching { app.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
    }
}
