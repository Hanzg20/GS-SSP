package com.goldsky.ssp.payment

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Technician switch between the two Aegis kiosk apps installed on one
 * terminal (test phase: Wash and Timer on bay5), without adb.
 *
 * The started app then asks to become the default HOME (Android's own
 * prompt, see claimHomeIfSwitched), so it is what comes back after a
 * reboot or the HOME key. This
 * app is then ended for good: left running in the background, its idle
 * timer pulls its ad screen back over the other app and its remote-command
 * listener still accepts starts for a machine it no longer serves.
 */
object AppSwitcher {
    private const val TAG = "AppSwitcher"

    private val APPS = listOf(
        "com.goldsky.ssp.aegis.wash" to "Aegis Wash",
        "com.goldsky.ssp.aegis.timer" to "Aegis Timer",
    )
    val AEGIS_PACKAGES = APPS.map { it.first }.toSet()

    /** Set on the launch intent: the started app asks to become the default HOME. */
    const val EXTRA_CLAIM_HOME = "com.goldsky.ssp.extra.CLAIM_HOME"
    private const val REQUEST_HOME_ROLE = 7301

    /** The other Aegis app installed on this terminal: (package, name), or null. */
    fun other(context: Context): Pair<String, String>? =
        APPS.firstOrNull { (pkg, _) ->
            pkg != context.packageName && context.packageManager.getLaunchIntentForPackage(pkg) != null
        }

    fun switchTo(activity: Activity, pkg: String) {
        val launch = activity.packageManager.getLaunchIntentForPackage(pkg) ?: return
        Log.w(TAG, "Switching to $pkg")
        activity.startActivity(
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_CLAIM_HOME, true)
        )
        activity.finishAffinity()
        // Give the other app a moment to come up and claim HOME first.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 1_500)
    }

    /**
     * Called from the started app's MainActivity: after a switch, shows
     * Android's own "set as default home app" prompt (one tap for the
     * technician), so a reboot or the HOME key comes back to this app.
     * The vendor's setDefaultLauncher can't move HOME from one app to
     * another on the Q3mini (Android 14) -- it leaves the HOME chooser.
     */
    fun claimHomeIfSwitched(activity: Activity) {
        if (!activity.intent.getBooleanExtra(EXTRA_CLAIM_HOME, false)) return
        activity.intent.removeExtra(EXTRA_CLAIM_HOME)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val roles = activity.getSystemService(RoleManager::class.java) ?: return
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return
        Log.i(TAG, "Asking to become the default HOME")
        runCatching {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE)
        }.onFailure { Log.w(TAG, "HOME role request failed: ${it.message}") }
    }
}
