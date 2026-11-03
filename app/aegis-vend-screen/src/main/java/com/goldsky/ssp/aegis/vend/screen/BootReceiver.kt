package com.goldsky.ssp.aegis.vend.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * After a reboot when the screen app isn't the display's HOME (which is what normally
 * brings it back). Same as Ourea Eye.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("BootReceiver", "Boot completed, launching Aegis Vend Screen")
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("BootReceiver", "Launch after boot refused: ${it.message}") }
    }
}
