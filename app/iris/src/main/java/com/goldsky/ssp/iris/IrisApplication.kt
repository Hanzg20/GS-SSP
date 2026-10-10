package com.goldsky.ssp.iris

import android.app.Application
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.iris.hardware.idtech.IdTechHardwareProvider
import com.goldsky.ssp.iris.hardware.pax.PaxHardwareProvider
import com.goldsky.ssp.iris.hardware.wizarpos.WizarPosHardwareProvider
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.payment.hardware.HardwareFactory

/**
 * Handheld POS shell. Same registration pattern as app/aegis-wash/AegisWashApplication.kt:
 * done here rather than in MainActivity so WorkManager jobs (BatchCloseWorker,
 * TransactionReplayWorker) run in a cold process still find the providers.
 */
class IrisApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val detectedModel = com.goldsky.ssp.DeviceAdapter.getModel()
        val recommendedVendor = com.goldsky.ssp.DeviceAdapter.getRecommendedVendor()
        android.util.Log.i("SSP_INIT", "Detected Model: $detectedModel, Recommended Vendor: $recommendedVendor")

        CoreConfig.isMock = BuildConfig.IS_MOCK
        HardwareConfig.isMock = BuildConfig.IS_MOCK
        // Must run before anything touches SupabaseClientProvider -- its `client`
        // is built eagerly from these on first access.
        CoreConfig.supabaseUrl = BuildConfig.SUPABASE_URL
        CoreConfig.supabaseKey = BuildConfig.SUPABASE_KEY

        // init() before getPaymentProvider(): WizarPos's payment provider
        // captures `terminal` by value on first call, null until init() runs.
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
