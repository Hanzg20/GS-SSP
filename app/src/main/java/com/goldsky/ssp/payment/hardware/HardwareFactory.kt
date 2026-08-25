package com.goldsky.ssp.payment.hardware

import android.content.Context
import com.goldsky.ssp.BuildConfig
import com.goldsky.ssp.payment.hardware.idtech.IdTechHardwareProvider
import com.goldsky.ssp.payment.hardware.pax.PaxHardwareProvider
import com.goldsky.ssp.payment.hardware.wizarpos.WizarPosHardwareProvider
import com.goldsky.ssp.payment.hardware.mock.MockHardwareProvider

/**
 * Factory to instantiate the correct hardware provider based on environment/config.
 */
object HardwareFactory {

    private val hardwareProviders = mutableMapOf<String, IHardwareProvider>()

    /**
     * returns the hardware provider for the current configuration.
     */
    fun getHardwareProvider(vendor: String = "IDTECH"): IHardwareProvider {
        android.util.Log.d("HardwareFactory", "getHardwareProvider($vendor), IS_MOCK=${BuildConfig.IS_MOCK}")
        if (BuildConfig.IS_MOCK) {
            return hardwareProviders.getOrPut("MOCK") { MockHardwareProvider() }
        }

        val key = vendor.uppercase()
        return hardwareProviders.getOrPut(key) {
            when (key) {
                "IDTECH" -> IdTechHardwareProvider()
                "PAX" -> PaxHardwareProvider()
                "WIZARPOS" -> WizarPosHardwareProvider()
                else -> throw IllegalArgumentException("Unknown hardware vendor: $vendor")
            }
        }
    }

    /**
     * returns the payment provider for the current configuration.
     */
    fun getPaymentProvider(context: Context, vendor: String = "IDTECH"): IPaymentProvider {
        val hardware = getHardwareProvider(vendor)
        hardware.init(context)
        
        return when (hardware) {
            is IdTechHardwareProvider -> hardware.getPaymentProvider()
            is PaxHardwareProvider -> hardware.getPaymentProvider()
            is WizarPosHardwareProvider -> hardware.getPaymentProvider()
            is MockHardwareProvider -> hardware.getPaymentProvider()
            else -> throw IllegalArgumentException("Unknown hardware provider type")
        }
    }

    /**
     * returns the scanner provider for the current configuration.
     */
    fun getScannerProvider(context: Context, vendor: String = "IDTECH"): IScannerProvider {
        val hardware = getHardwareProvider(vendor)
        return hardware.getScannerProvider()
    }

    /**
     * returns the printer provider for the current configuration.
     */
    fun getPrinterProvider(context: Context, vendor: String = "IDTECH"): IPrinterProvider {
        val hardware = getHardwareProvider(vendor)
        hardware.init(context)
        return hardware.getPrinterProvider()
    }

    /**
     * returns the serial communication provider for the current configuration.
     */
    fun getSerialProvider(context: Context, vendor: String = "IDTECH"): ISerialProvider {
        val hardware = getHardwareProvider(vendor)
        hardware.init(context)
        return hardware.getSerialProvider()
    }

    /**
     * returns the MDB provider for the current configuration.
     */
    fun getMdbProvider(context: Context, vendor: String = "IDTECH"): IMdbProvider {
        val hardware = getHardwareProvider(vendor)
        hardware.init(context)
        return hardware.getMdbProvider()
    }

    /**
     * returns the GPIO provider for the current configuration.
     */
    fun getGpioProvider(context: Context, vendor: String = "IDTECH"): IGpioProvider {
        val hardware = getHardwareProvider(vendor)
        hardware.init(context)
        return hardware.getGpioProvider()
    }
}
