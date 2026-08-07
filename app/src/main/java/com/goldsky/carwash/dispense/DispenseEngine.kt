package com.goldsky.carwash.dispense

import com.goldsky.carwash.dispense.adapter.*
import com.goldsky.carwash.dispense.ack.AssumedSuccessAckStrategy
import com.goldsky.carwash.dispense.ack.FramedAckStrategy
import com.goldsky.carwash.payment.ConfigManager
import com.goldsky.carwash.payment.hardware.HardwareFactory
import com.goldsky.carwash.payment.hardware.ISerialProvider

/**
 * Single entry point MainActivity calls instead of talking to
 * SerialPortManager/pulse math directly. Resolves which [IDispenseAdapter]
 * (what to send) and which [IAckStrategy] (how to know it ran) apply to
 * *this device* from KioskSettings.dispense_protocol/dispense_ack_mode --
 * both are device-level config (same product runs on many different
 * physical device generations), not per-product attributes.
 *
 * Unknown/missing config values fall back to "pulse_credit" + "framed_ack",
 * i.e. today's only real deployment (car wash), so existing devices with no
 * dispense_protocol set in their cloud config keep working unchanged.
 */
object DispenseEngine {
    suspend fun dispense(
        job: DispenseJob,
        isSimulationMode: Boolean,
        hardwareVendor: String = "IDTECH",
        onProgress: (unitsSent: Int, totalUnits: Int) -> Unit = { _, _ -> }
    ): DispenseOutcome {
        val serialProvider = HardwareFactory.getSerialProvider(null as? android.content.Context ?: job.contextReference, hardwareVendor)
        
        if (isSimulationMode) return MockAdapter().dispense(job, FramedAckStrategy, serialProvider, onProgress)

        val settings = ConfigManager.getConfig()?.settings
        val adapter = when (settings?.dispense_protocol) {
            "single_command" -> SingleCommandAdapter()
            "mdb_vend" -> MdbVendAdapter()
            else -> PulseCreditAdapter()
        }
        val ackStrategy = when (settings?.dispense_ack_mode) {
            "assumed_success" -> AssumedSuccessAckStrategy()
            else -> FramedAckStrategy
        }
        return adapter.dispense(job, ackStrategy, serialProvider, onProgress)
    }
}

// Extension to pass context if needed from the job
private val DispenseJob.contextReference: android.content.Context get() = error("DispenseEngine requires serial provider access")
