package com.goldsky.ssp.payment.hardware

import android.content.Context
import com.goldsky.ssp.common.HardwareConfig

/**
 * Factory to instantiate the correct hardware provider based on environment/config.
 * Supports dynamic registration of providers (Plugin architecture).
 */
object HardwareFactory {

    private val hardwareProviders = mutableMapOf<String, IHardwareProvider>()

    /**
     * Registers a vendor-specific hardware provider.
     */
    fun registerHardwareProvider(vendor: String, provider: IHardwareProvider) {
        hardwareProviders[vendor.uppercase()] = provider
    }

    /**
     * returns the hardware provider for the current configuration.
     */
    fun getHardwareProvider(vendor: String = "IDTECH"): IHardwareProvider {
        val key = vendor.uppercase()
        return hardwareProviders[key] ?: throw IllegalArgumentException("No hardware provider registered for vendor: $vendor")
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
