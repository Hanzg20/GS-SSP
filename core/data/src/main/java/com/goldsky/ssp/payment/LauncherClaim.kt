package com.goldsky.ssp.payment

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.goldsky.ssp.payment.hardware.HardwareFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Makes this kiosk app the terminal's default HOME, so it is what comes back
 * after a power cut. Only asks the vendor when the current default HOME is
 * something else (another launcher, or the chooser) -- measured on the
 * Q3mini 2026-09-28: calling it from two installed apps at every start made
 * the next boot open the HOME chooser, while a single call left the kiosk as
 * HOME across reboots (with OPC in background mode, see the deployment
 * checklist).
 */
object LauncherClaim {
    private const val TAG = "LauncherClaim"

    fun ensureDefault(context: Context, vendor: String) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val current = currentHomePackage(app)
            if (holdsHome(app) ?: (current == app.packageName)) {
                Log.i(TAG, "Already the default HOME")
                // WizarPOS keeps its own boot launcher (persist.wp.launcher) and starts -- and
                // re-enables -- that app at boot whatever Android's HOME role says (255,
                // 2026-11-02: HOME = Vend, Timer disabled, yet Timer came back at boot). Bring it
                // in line with the role this app already holds; never taken from anyone here.
                // The vendor call clears Android's HOME choice (255: the chooser came up once),
                // so after it set HOME again: `adb shell cmd package set-home-activity <pkg>/<activity>`
                // or "Always" on the chooser. Runs only while the two disagree, i.e. once.
                val wp = systemProperty("persist.wp.launcher")
                if (!wp.isNullOrBlank() && !wp.startsWith(app.packageName + "/")) {
                    Log.i(TAG, "WizarPOS boot launcher is $wp, aligning it with HOME")
                    runCatching { HardwareFactory.getHardwareProvider(vendor).setDefaultLauncher(app.packageName) }
                        .onFailure { Log.w(TAG, "setDefaultLauncher failed: ${it.message}") }
                }
                return@launch
            }
            if (current != null && current in AppSwitcher.AEGIS_PACKAGES) {
                // Another Aegis app is HOME: the vendor call would leave the
                // HOME chooser on Android 14 (measured 2026-10-07). Moving
                // HOME between them is the technician switch's job
                // (AppSwitcher: Android's own "set as default home" prompt).
                Log.i(TAG, "Default HOME is $current, leaving it")
                return@launch
            }
            Log.i(TAG, "Default HOME is $current, claiming it")
            runCatching { HardwareFactory.getHardwareProvider(vendor).setDefaultLauncher(app.packageName) }
                .onFailure { Log.w(TAG, "setDefaultLauncher failed: ${it.message}") }
        }
    }

    /**
     * Whether this app holds the HOME role (Android 10+). The resolveActivity
     * answer can't be trusted for that on the Q3mini (Android 14): measured
     * 2026-10-07, Timer logged "Already the default HOME" while
     * `cmd role get-role-holders android.app.role.HOME` was Aegis Wash.
     */
    private fun holdsHome(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) }.getOrNull()
    }

    private fun systemProperty(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()

    /** Package of the current default HOME; "android" when the chooser would show. */
    private fun currentHomePackage(context: Context): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }
}
