package com.goldsky.carwash.payment.hardware

import android.content.Context
import com.goldsky.carwash.payment.hardware.idtech.IdTechHardwareProvider
import com.goldsky.carwash.payment.hardware.pax.PaxHardwareProvider
import com.goldsky.carwash.payment.hardware.pax.PaxPaymentProvider
import com.goldsky.carwash.payment.hardware.pax.PaxScannerProvider

/**
 * Factory to instantiate the correct hardware provider based on environment/config.
 */
object HardwareFactory {

    private var hardwareProvider: IHardwareProvider? = null

    /**
     * returns the hardware provider for the current configuration.
     * Currently toggled via a hardcoded flag or BuildConfig.
     */
    fun getHardwareProvider(vendor: String = "IDTECH"): IHardwareProvider {
        if (hardwareProvider == null) {
            hardwareProvider = when (vendor.uppercase()) {
                "IDTECH" -> IdTechHardwareProvider()
                "PAX" -> PaxHardwareProvider()
                else -> throw IllegalArgumentException("Unknown hardware vendor: $vendor")
            }
        }
        return hardwareProvider!!
    }

    /**
     * returns the payment provider for the current configuration.
     */
    fun getPaymentProvider(context: Context, vendor: String = "IDTECH"): IPaymentProvider {
        val hardware = getHardwareProvider(vendor)
        hardware.init(context)
        return when (hardware) {
            is IdTechHardwareProvider -> hardware.getPaymentProvider()
            is PaxHardwareProvider -> PaxPaymentProvider(context)
            else -> throw IllegalArgumentException("Unknown hardware vendor: $vendor")
        }
    }

    /**
     * returns the scanner provider for the current configuration.
     */
    fun getScannerProvider(context: Context, vendor: String = "IDTECH"): IScannerProvider {
        val hardware = getHardwareProvider(vendor)
        return when (hardware) {
            is IdTechHardwareProvider -> {
                object : IScannerProvider {
                    override fun startScan(callback: IScannerProvider.ScanCallback) {}
                    override fun stopScan() {}
                    override fun setScannerLed(enabled: Boolean) {}
                }
            }
            is PaxHardwareProvider -> PaxScannerProvider(context)
            else -> throw IllegalArgumentException("Unknown hardware vendor: $vendor")
        }
    }
}
