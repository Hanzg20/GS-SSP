package com.goldsky.carwash.dispense.adapter

import com.goldsky.carwash.dispense.DispenseJob
import com.goldsky.carwash.dispense.DispenseOutcome
import com.goldsky.carwash.dispense.IAckStrategy
import com.goldsky.carwash.dispense.IDispenseAdapter
import kotlinx.coroutines.delay

/**
 * Simulation-mode path -- unchanged behavior from the original
 * `isSimulationMode` branch in MainActivity.startFinalizationSequence
 * (fixed delay, always succeeds). Ignores [ackStrategy] entirely since there
 * is no hardware to ask.
 */
class MockAdapter : IDispenseAdapter {
    override suspend fun dispense(job: DispenseJob, ackStrategy: IAckStrategy): DispenseOutcome {
        delay(1500)
        return DispenseOutcome.Confirmed("simulated")
    }
}
