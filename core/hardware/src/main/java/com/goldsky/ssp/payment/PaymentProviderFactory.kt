package com.goldsky.ssp.payment

import android.content.Context
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IPaymentProvider

/**
 * Registry for payment providers. 
 * Decouples core payment from vendor-specific implementations in app modules.
 */
object PaymentProviderFactory {

    private val paymentProviders = mutableMapOf<String, IPaymentProvider>()

    fun registerPaymentProvider(vendor: String, provider: IPaymentProvider) {
        paymentProviders[vendor.uppercase()] = provider
    }

    fun getPaymentProvider(context: Context, vendor: String = "IDTECH"): IPaymentProvider {
        // Force hardware init via HardwareFactory if applicable
        try {
            HardwareFactory.getHardwareProvider(vendor).init(context)
        } catch (e: Exception) {}

        return paymentProviders[vendor.uppercase()] 
            ?: throw IllegalArgumentException("No payment provider registered for vendor: $vendor")
    }
}
