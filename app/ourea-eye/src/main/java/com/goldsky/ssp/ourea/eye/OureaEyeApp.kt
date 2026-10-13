package com.goldsky.ssp.ourea.eye

import android.app.Application
import android.provider.Settings
import android.util.Log
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.iris.hardware.wizarpos.WizarPosHardwareProvider
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.SupabaseClientProvider
import com.goldsky.ssp.payment.hardware.HardwareFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ourea Eye: the WizarPOS Q3mini beside the counter -- customer display and,
 * through its PAYWizard, the card terminal Ourea charges on.
 *
 * It registers like every other GoldSky device (real serial, heartbeat, link
 * to its store), so CMP can see it and give it an ad playlist for the idle
 * screen. Without that it never had a playlist and only ever showed the
 * built-in fallback.
 */
class OureaEyeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoreConfig.isMock = BuildConfig.IS_MOCK
        HardwareConfig.isMock = BuildConfig.IS_MOCK
        CoreConfig.supabaseUrl = BuildConfig.SUPABASE_URL
        CoreConfig.supabaseKey = BuildConfig.SUPABASE_KEY

        DeviceRepository.init(this)

        // Only for the serial number; payments go through Ourea, not this app.
        val wizarPos = WizarPosHardwareProvider()
        wizarPos.init(this)
        HardwareFactory.registerHardwareProvider("WIZARPOS", wizarPos)
        DeviceRepository.persistHardwareVendor("WIZARPOS")

        val reported = runCatching { wizarPos.getSerialNumber(this) }.getOrNull()
        // No usable serial (emulator, SDK not ready): a stable per-install id rather
        // than a shared placeholder like "unknown", which would merge devices.
        val sn = reported?.takeIf { it.isNotBlank() && !it.startsWith("WIZAR_SN_") }
            ?: ("EYE_" + (Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"))
        DeviceRepository.persistDeviceSn(sn)
        Log.i("OureaEye", "Device SN: $sn")

        // Heartbeat (now + on every reconnect), ad playlist sync every 2 h.
        AdManager.init(this, syncAds = true)

        CoroutineScope(Dispatchers.IO).launch {
            SupabaseClientProvider.ensureAuthenticated()
            DeviceRepository.registerDevice(sn, BuildConfig.VERSION_NAME)
            DeviceRepository.syncDeviceIdentity(sn)
            AdManager.syncNow(this@OureaEyeApp) // the playlist right away, not in 2 h
        }
    }
}
