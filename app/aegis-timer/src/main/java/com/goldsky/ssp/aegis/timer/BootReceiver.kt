package com.goldsky.ssp.aegis.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.goldsky.ssp.feature.timer.MainActivity

/**
 * Brings the kiosk back after a reboot. Android 14 blocks this background
 * start (BAL_BLOCK) unless the app holds "display over other apps"
 * (SYSTEM_ALERT_WINDOW). On the Q3mini engineering unit the start is then
 * allowed but still returns 101 while WizarPOS's OPC holds the screen after
 * boot -- open question with WizarPOS (2026-09-27).
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
