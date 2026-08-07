package com.goldsky.carwash.dispense

import com.goldsky.carwash.payment.hardware.ISerialProvider

/**
 * Speaks one device's dispense protocol (how many commands, with what
 * payload, in what shape). Delegates the "did it actually run" question to
 * the injected [IAckStrategy] instead of deciding it itself, so the same
 * adapter can be reused on hardware generations with and without feedback.
 *
 * [onProgress] reports (unitsSent, totalUnits) as the adapter works through
 * its own notion of a "unit" (one pulse for [com.goldsky.carwash.dispense.adapter.PulseCreditAdapter],
 * one command for [com.goldsky.carwash.dispense.adapter.SingleCommandAdapter]) --
 * it exists so the UI can show real progress during the unattended dispense
 * wait instead of a fixed timer, not to expose protocol internals.
 */
interface IDispenseAdapter {
    suspend fun dispense(
        job: DispenseJob,
        ackStrategy: IAckStrategy,
        serialProvider: ISerialProvider,
        onProgress: (unitsSent: Int, totalUnits: Int) -> Unit = { _, _ -> }
    ): DispenseOutcome
}
