package com.goldsky.ssp.aegis.ev

import android.app.Application
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.iris.hardware.idtech.IdTechHardwareProvider
import com.goldsky.ssp.iris.hardware.pax.PaxHardwareProvider
import com.goldsky.ssp.iris.hardware.wizarpos.WizarPosHardwareProvider
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.payment.hardware.HardwareFactory

/**
 * EV charging shell (feature:ev), part of the Aegis unattended-terminal series.
 * Same registration pattern as app/aegis-wash/AegisWashApplication.kt / app/aegis-vend/AegisVendApplication.kt.
 */
class AegisEvApplication : Application() {
    override fun onCreate() {
        super.onCreate()

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
