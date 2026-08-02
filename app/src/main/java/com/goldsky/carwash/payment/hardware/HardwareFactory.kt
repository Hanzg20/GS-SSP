package com.goldsky.carwash.payment.hardware

import android.content.Context
import com.goldsky.carwash.payment.hardware.idtech.IdTechHardwareProvider

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
                // "PAX" -> PaxHardwareProvider() // Future implementation
                else -> throw IllegalArgumentException("Unknown hardware vendor: $vendor")
            }
        }
        return hardwareProvider!!
    }

    /**
     * returns the payment provider for the current configuration. The IdTech
     * payment provider is owned by [IdTechHardwareProvider] (it's also the SDK's
     * registered OnReceiverListener), so this fetches the same instance rather
     * than constructing a second, disconnected one. Calls [getHardwareProvider]'s
     * `init` defensively in case the caller never did (it's a no-op if already
     * initialized).
     */
    fun getPaymentProvider(context: Context, vendor: String = "IDTECH"): IPaymentProvider {
        val hardware = getHardwareProvider(vendor)
        hardware.init(context)
        return when (hardware) {
            is IdTechHardwareProvider -> hardware.getPaymentProvider()
            else -> throw IllegalArgumentException("Unknown hardware vendor: $vendor")
        }
    }

    /**
     * returns the scanner provider for the current configuration.
     *
     * NOT IMPLEMENTED for ID TECH: this is a silent no-op (callbacks never fire,
     * LED control does nothing). The existing member/coupon QR scan flow still
     * goes through PaxScannerManager directly and does not call this. Do not
     * route new scan features through this until a real implementation exists --
     * they would hang forever waiting on a callback that never arrives.
     */
    fun getScannerProvider(context: Context, vendor: String = "IDTECH"): IScannerProvider {
        return object : IScannerProvider {
            override fun startScan(callback: IScannerProvider.ScanCallback) {}
            override fun stopScan() {}
            override fun setScannerLed(enabled: Boolean) {}
        }
    }
}
