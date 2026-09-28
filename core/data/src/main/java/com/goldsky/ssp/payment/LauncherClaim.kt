package com.goldsky.ssp.payment

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
            if (current == app.packageName) {
                Log.i(TAG, "Already the default HOME")
                return@launch
            }
            Log.i(TAG, "Default HOME is $current, claiming it")
            runCatching { HardwareFactory.getHardwareProvider(vendor).setDefaultLauncher(app.packageName) }
                .onFailure { Log.w(TAG, "setDefaultLauncher failed: ${it.message}") }
        }
    }

    /** Package of the current default HOME; "android" when the chooser would show. */
    private fun currentHomePackage(context: Context): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }
}
