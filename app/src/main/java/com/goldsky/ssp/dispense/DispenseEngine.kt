package com.goldsky.ssp.dispense

import com.goldsky.ssp.dispense.adapter.*
import com.goldsky.ssp.dispense.ack.AssumedSuccessAckStrategy
import com.goldsky.ssp.dispense.ack.FramedAckStrategy
import com.goldsky.ssp.DeviceAdapter
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.ISerialProvider

/**
 * Single entry point MainActivity calls instead of talking to
 * SerialPortManager/pulse math directly.
 */
object DispenseEngine {
    suspend fun dispense(
        job: DispenseJob,
        isSimulationMode: Boolean,
        hardwareVendor: String = "IDTECH",
        onProgress: (unitsSent: Int, totalUnits: Int) -> Unit = { _, _ -> }
    ): DispenseOutcome {
        val model = DeviceAdapter.getModel()
        val serialProvider = HardwareFactory.getSerialProvider(null as? android.content.Context ?: job.contextReference, hardwareVendor)
        
        if (isSimulationMode) return MockAdapter().dispense(job, FramedAckStrategy, serialProvider, onProgress)

        val config = ConfigManager.getConfig()
        val settings = config?.settings
        
        // Priority: If it's a UPT machine with Digit IO, and we're in WASH mode, use DigitIoAdapter.
        // This addresses Andy's scheme of direct relay control.
        val modelStr = model.toString()
        val isUptMachine = modelStr.contains("Q3MINI") || modelStr.contains("IM30")
        
        val adapter = if (isUptMachine && config?.vertical_type == "WASH") {
            DigitIoAdapter()
        } else {
            when (settings?.dispense_protocol) {
                "single_command" -> SingleCommandAdapter()
                "mdb_vend" -> MdbVendAdapter()
                "edgenexus_remote" -> EdgeNexusRemoteAdapter()
                else -> PulseCreditAdapter()
            }
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
