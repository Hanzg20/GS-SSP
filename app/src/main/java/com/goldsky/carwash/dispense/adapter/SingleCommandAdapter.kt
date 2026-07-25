package com.goldsky.carwash.dispense.adapter

import com.goldsky.carwash.dispense.AckConfidence
import com.goldsky.carwash.dispense.DispenseJob
import com.goldsky.carwash.dispense.DispenseOutcome
import com.goldsky.carwash.dispense.IAckStrategy
import com.goldsky.carwash.dispense.IDispenseAdapter

/**
 * "Select and go" devices (coffee machine, noodle machine, mode-select relay
 * boards): one command carries everything the board needs (recipe/mode
 * selection baked into [DispenseJob.startHex]), no per-cent pulse math. The
 * board is trusted to run its own cycle/timer after accepting the command.
 */
class SingleCommandAdapter : IDispenseAdapter {
    override suspend fun dispense(
        job: DispenseJob,
        ackStrategy: IAckStrategy,
        onProgress: (Int, Int) -> Unit
    ): DispenseOutcome {
        onProgress(0, 1)
        val outcome = when (ackStrategy.confirm(job.startHex)) {
            AckConfidence.CONFIRMED -> DispenseOutcome.Confirmed()
            AckConfidence.UNCONFIRMED -> DispenseOutcome.DeliveredUnconfirmed()
            AckConfidence.FAILED -> return DispenseOutcome.Failed("command '${job.startHex}' not delivered")
        }
        onProgress(1, 1)
        return outcome
    }
}
