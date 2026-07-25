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
    override suspend fun dispense(
        job: DispenseJob,
        ackStrategy: IAckStrategy,
        onProgress: (Int, Int) -> Unit
    ): DispenseOutcome {
        // Reports progress in 4 even steps over the same total delay as
        // before, purely so the wash-stage stepper has something to animate
        // in simulation mode too -- most day-to-day testing happens here.
        val steps = 4
        repeat(steps) { i ->
            delay(1500L / steps)
            onProgress(i + 1, steps)
        }
        return DispenseOutcome.Confirmed("simulated")
    }
}
