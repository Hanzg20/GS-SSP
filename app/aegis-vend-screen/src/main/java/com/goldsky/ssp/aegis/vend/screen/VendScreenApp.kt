package com.goldsky.ssp.aegis.vend.screen

import android.app.Application
import android.provider.Settings
import android.util.Log
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.SupabaseClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Aegis Vend Screen: an ordinary Android display beside a vending machine. It shows what the
 * machine's Aegis Vend terminal shows, in large type (VendScreenLink, over the cloud), and
 * Cael ads while the machine is idle.
 *
 * It registers like every GoldSky device -- so Cael lists it, assigns it to the store and
 * gives it a playlist -- under a per-install id (no payment hardware, no vendor serial).
 */
class VendScreenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoreConfig.isMock = BuildConfig.IS_MOCK
        HardwareConfig.isMock = BuildConfig.IS_MOCK
        CoreConfig.supabaseUrl = BuildConfig.SUPABASE_URL
        CoreConfig.supabaseKey = BuildConfig.SUPABASE_KEY

        DeviceRepository.init(this)
        val sn = DeviceRepository.getPersistedDeviceSn()
            ?: ("VSCR_" + (Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN").uppercase())
        DeviceRepository.persistDeviceSn(sn)
        Log.i(TAG, "Device SN: $sn")

        // Heartbeat, and the ad playlist every 2 h.
        AdManager.init(this, syncAds = true)

        CoroutineScope(Dispatchers.IO).launch {
            SupabaseClientProvider.ensureAuthenticated()
            DeviceRepository.registerDevice(sn, BuildConfig.VERSION_NAME)
            DeviceRepository.syncDeviceIdentity(sn)
            // The store's settings, for the technician PIN (same as the Aegis kiosks).
            runCatching { ConfigManager.loadConfig(this@VendScreenApp, DeviceRepository.getPersistedOrgId()) }
            AdManager.syncNow(this@VendScreenApp) // the playlist now, not in 2 h
        }
    }

    private companion object {
        const val TAG = "VendScreen"
    }
}
