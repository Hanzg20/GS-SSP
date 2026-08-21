package com.goldsky.ssp.dispense.adapter

import com.goldsky.ssp.dispense.*
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.ISerialProvider
import com.goldsky.ssp.DeviceAdapter
import kotlinx.coroutines.delay

/**
 * Andy's Scheme: Directly trigger the hardware relay via GPIO (Digit IO).
 * This adapter bypasses the serial port and uses the vendor's GPIO provider.
 * Ideal for Wash and EV scenarios on Q3mini/IM30.
 */
class DigitIoAdapter : IDispenseAdapter {
    private val TAG = "DigitIoAdapter"

    override suspend fun dispense(
        job: DispenseJob,
        ackStrategy: IAckStrategy,
        serialProvider: ISerialProvider,
        onProgress: (Int, Int) -> Unit
    ): DispenseOutcome {
        android.util.Log.i(TAG, "Initiating GPIO-based dispense for ${job.amountCents} cents")
        
        // 1. Get the GPIO provider for the current hardware
        val modelStr = DeviceAdapter.getModel().toString()
        val vendor = if (modelStr.contains("WIZARPOS")) "WIZARPOS" else "PAX"
        val gpioProvider = HardwareFactory.getGpioProvider(null as? android.content.Context ?: job.contextReference, vendor)

        // 2. Map amount to pulse or just a long activation
        onProgress(0, 100)
        return try {
            gpioProvider.setRelay(0, true)
            android.util.Log.i(TAG, "Physical Relay 0 turned ON")
            
            // Progress simulation for UI
            for (i in 1..10) {
                onProgress(i * 10, 100)
                delay(500)
            }
            
            gpioProvider.setRelay(0, false)
            android.util.Log.i(TAG, "Physical Relay 0 turned OFF")
            
            DispenseOutcome.Confirmed("Hardware Relay cycle completed")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "GPIO hardware fault: ${e.message}")
            DispenseOutcome.Failed("GPIO fault: ${e.message}")
        }
    }
}

// Internal helper to get context if needed
private val DispenseJob.contextReference: android.content.Context get() = error("DispenseEngine requires serial provider access")
