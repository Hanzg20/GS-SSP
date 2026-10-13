package com.goldsky.ssp.ourea.eye

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fallback for after a reboot when the Eye isn't the default HOME (which is
 * what normally brings it back -- see LauncherClaim). Same as Aegis Timer.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("BootReceiver", "Boot completed, launching Ourea Eye")
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("BootReceiver", "Launch after boot refused: ${it.message}") }
    }
}
