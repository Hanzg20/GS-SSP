package com.goldsky.ssp.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * The technician's way to change the terminal's network on site: Wi-Fi, or the terminal's
 * own SIM (mobile data) when the site's Wi-Fi is down or changed (owner, 2026-10-20). Opens
 * Android's "Network & internet" page, where both are; failing that Wi-Fi, then Settings.
 * The kiosk is the HOME app, so Back or Home returns to it.
 */
object NetworkSettings {
    fun open(activity: Activity) {
        val tries = listOf(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS)
        for (action in tries) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(activity.packageManager) == null) continue
            if (runCatching { activity.startActivity(intent) }.isSuccess) return
        }
        Log.w("NetworkSettings", "No network settings screen on this terminal")
    }
}
