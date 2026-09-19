package com.goldsky.ssp.aegis.wash

import android.app.Application
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.iris.hardware.idtech.IdTechHardwareProvider
import com.goldsky.ssp.iris.hardware.pax.PaxHardwareProvider
import com.goldsky.ssp.iris.hardware.wizarpos.WizarPosHardwareProvider
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.payment.hardware.HardwareFactory

/**
 * Registers every vendor's hardware/payment provider before any Activity
 * (feature.wash.MainActivity is the launcher, declared directly in the
 * manifest with no app-local wrapper Activity) can run -- Application.onCreate()
 * is the only hook that's guaranteed to fire first. Mirrors app/iris/MainActivity's
 * registration exactly; see that file's comment for why init() must run before
 * getPaymentProvider() on each provider.
 */
class AegisWashApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val detectedModel = com.goldsky.ssp.DeviceAdapter.getModel()
        val recommendedVendor = com.goldsky.ssp.DeviceAdapter.getRecommendedVendor()
        android.util.Log.i("SSP_INIT", "Detected Model: $detectedModel, Recommended Vendor: $recommendedVendor")

        CoreConfig.isMock = BuildConfig.IS_MOCK
        HardwareConfig.isMock = BuildConfig.IS_MOCK
        CoreConfig.supabaseUrl = BuildConfig.SUPABASE_URL
        CoreConfig.supabaseKey = BuildConfig.SUPABASE_KEY

        val idTechProvider = IdTechHardwareProvider()
        val paxProvider = PaxHardwareProvider()
        val wizarPosProvider = WizarPosHardwareProvider()

        idTechProvider.init(this)
        paxProvider.init(this)
        wizarPosProvider.init(this)

        HardwareFactory.registerHardwareProvider("IDTECH", idTechProvider)
        HardwareFactory.registerHardwareProvider("PAX", paxProvider)
        HardwareFactory.registerHardwareProvider("WIZARPOS", wizarPosProvider)

        PaymentProviderFactory.registerPaymentProvider("IDTECH", idTechProvider.getPaymentProvider())
        PaymentProviderFactory.registerPaymentProvider("PAX", paxProvider.getPaymentProvider())
        PaymentProviderFactory.registerPaymentProvider("WIZARPOS", wizarPosProvider.getPaymentProvider())
    }
}
