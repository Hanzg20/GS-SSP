package com.goldsky.carwash.dispense

/**
 * Speaks one device's dispense protocol (how many commands, with what
 * payload, in what shape). Delegates the "did it actually run" question to
 * the injected [IAckStrategy] instead of deciding it itself, so the same
 * adapter can be reused on hardware generations with and without feedback.
 */
interface IDispenseAdapter {
    suspend fun dispense(job: DispenseJob, ackStrategy: IAckStrategy): DispenseOutcome
}
