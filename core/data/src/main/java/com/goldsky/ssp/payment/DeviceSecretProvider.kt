package com.goldsky.ssp.payment

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.util.Log

/**
 * Hands this terminal's identity secret (sync_device_identity) to our other apps on the
 * same terminal. The server keeps one secret per device; a development terminal with two
 * of our apps (e.g. Aegis Wash and Aegis Timer on 255) had the second one refused forever
 * (invalid_secret), since only the first got the secret. Now the second borrows it
 * (DeviceRepository.siblingSecret).
 *
 * Only an app signed with this app's own key gets an answer: checked here, not with a
 * custom signature permission -- two apps declaring the same permission with different
 * keys (a debug build next to a release build) can't both be installed.
 */
class DeviceSecretProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val caller = Binder.getCallingUid()
        if (ctx.packageManager.checkSignatures(caller, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            Log.w(TAG, "Secret asked for by uid $caller, not signed like us: refused")
            return null
        }
        val prefs = ctx.getSharedPreferences(DeviceRepository.PREFS_NAME, Context.MODE_PRIVATE)
        return MatrixCursor(arrayOf(COL_SN, COL_SECRET)).apply {
            val secret = prefs.getString(DeviceRepository.KEY_DEVICE_SECRET, null)
            if (secret != null) addRow(arrayOf(prefs.getString(DeviceRepository.KEY_DEVICE_SN, null), secret))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val TAG = "DeviceSecretProvider"
        const val COL_SN = "sn"
        const val COL_SECRET = "secret"

        /** Our apps that may share a terminal (manifest <queries> lists the same). */
        val APPS = listOf(
            "com.goldsky.ssp.aegis.wash",
            "com.goldsky.ssp.aegis.timer",
            "com.goldsky.ssp.aegis.vend",
            "com.goldsky.ssp.aegis.ev",
            "com.goldsky.ssp.iris",
            "com.goldsky.ssp.ourea",
            "com.goldsky.ssp.ourea.eye",
        )

        fun uri(pkg: String): Uri = Uri.parse("content://$pkg.devicesecret")
    }
}
