package com.goldsky.ssp.aegis.flex

import android.app.Application
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.iris.hardware.idtech.IdTechHardwareProvider
import com.goldsky.ssp.iris.hardware.pax.PaxHardwareProvider
import com.goldsky.ssp.iris.hardware.wizarpos.WizarPosHardwareProvider
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.payment.hardware.HardwareFactory

/**
 * Aegis Flex shell (feature:flex), part of the Aegis unattended-terminal series: the terminal
 * takes the money, a partner's own controller hands the product over. Same registration as
 * app/aegis-vend/AegisVendApplication.kt -- see app/aegis-wash/AegisWashApplication.kt for why
 * order matters (init() before getPaymentProvider()).
 */
class AegisFlexApplication : Application() {
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
