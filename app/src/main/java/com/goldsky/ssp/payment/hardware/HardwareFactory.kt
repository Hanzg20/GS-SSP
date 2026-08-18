package com.goldsky.ssp.payment.hardware

import android.content.Context
import com.goldsky.ssp.payment.hardware.idtech.IdTechHardwareProvider
import com.goldsky.ssp.payment.hardware.pax.PaxHardwareProvider
import com.goldsky.ssp.payment.hardware.wizarpos.WizarPosHardwareProvider

/**
 * Factory to instantiate the correct hardware provider based on environment/config.
 */
object HardwareFactory {

    // Keyed by vendor -- a single unkeyed nullable var previously meant
    // requesting a second vendor after the first silently returned the
    // first vendor's cached instance instead of the one actually asked for.
    private val hardwareProviders = mutableMapOf<String, IHardwareProvider>()

    /**
     * returns the hardware provider for the current configuration.
     * Currently toggled via a hardcoded flag or BuildConfig.
     */
    fun getHardwareProvider(vendor: String = "IDTECH"): IHardwareProvider {
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
     * returns the payment provider for the current configuration. Always the
     * same instance per vendor for the app's lifetime (both hardware
     * providers own and cache theirs) so provider-level state -- e.g.
     * PaxPaymentProvider.updateConfig() -- survives across calls.
     */
    fun getPaymentProvider(context: Context, vendor: String = "IDTECH"): IPaymentProvider {
        val hardware = getHardwareProvider(vendor)
        hardware.init(context)
        return when (hardware) {
            is IdTechHardwareProvider -> hardware.getPaymentProvider()
            is PaxHardwareProvider -> hardware.getPaymentProvider()
            is WizarPosHardwareProvider -> hardware.getPaymentProvider()
            else -> throw IllegalArgumentException("Unknown hardware vendor: $vendor")
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
     * returns the serial communication provider for the current configuration.
     */
    fun getSerialProvider(context: Context, vendor: String = "IDTECH"): ISerialProvider {
        val hardware = getHardwareProvider(vendor)
        hardware.init(context)
        return hardware.getSerialProvider()
    }
}
