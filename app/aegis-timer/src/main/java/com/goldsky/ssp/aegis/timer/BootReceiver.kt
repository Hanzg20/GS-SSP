package com.goldsky.ssp.aegis.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.goldsky.ssp.feature.timer.MainActivity

/**
 * Brings the kiosk back after a reboot when Aegis Timer isn't the default
 * HOME (as HOME, the system already opens it). Android 14 may block this
 * background start; HOME is the reliable path.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("BootReceiver", "Boot completed, launching Aegis Timer")
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("BootReceiver", "Launch after boot refused: ${it.message}") }
    }
}
