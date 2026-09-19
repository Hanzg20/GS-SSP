package com.goldsky.ssp.dispense

import com.goldsky.ssp.payment.hardware.IGpioProvider
import com.goldsky.ssp.payment.hardware.ISerialProvider

/**
 * Speaks one device's dispense protocol (how many commands, with what
 * payload, in what shape). Delegates the "did it actually run" question to
 * the injected [IAckStrategy] instead of deciding it itself, so the same
 * adapter can be reused on hardware generations with and without feedback.
 *
 * [gpioProvider] is only used by [com.goldsky.ssp.dispense.adapter.DigitIoAdapter]
 * (direct relay control, no serial framing) -- every other adapter ignores it and
 * talks to [serialProvider] instead. Caller (DispenseEngine) resolves it with a real
 * Context up front so adapters never need to reach into HardwareFactory themselves.
 *
 * [onProgress] reports (unitsSent, totalUnits) as the adapter works through
 * its own notion of a "unit" (one pulse for [com.goldsky.ssp.dispense.adapter.PulseCreditAdapter],
 * one command for [com.goldsky.ssp.dispense.adapter.SingleCommandAdapter]) --
 * it exists so the UI can show real progress during the unattended dispense
 * wait instead of a fixed timer, not to expose protocol internals.
 */
interface IDispenseAdapter {
    suspend fun dispense(
        job: DispenseJob,
        ackStrategy: IAckStrategy,
        serialProvider: ISerialProvider,
        gpioProvider: IGpioProvider? = null,
        onProgress: (unitsSent: Int, totalUnits: Int) -> Unit = { _, _ -> }
    ): DispenseOutcome
}
