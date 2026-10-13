package com.goldsky.ssp.ourea.eye

import android.app.Application
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.payment.DeviceRepository

class OureaEyeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoreConfig.isMock = BuildConfig.IS_MOCK
        HardwareConfig.isMock = BuildConfig.IS_MOCK
        CoreConfig.supabaseUrl = BuildConfig.SUPABASE_URL
        CoreConfig.supabaseKey = BuildConfig.SUPABASE_KEY

        DeviceRepository.init(this)
        AdManager.init(this, syncAds = true)
    }
}
