package com.goldsky.carwash.dispense.adapter

import com.goldsky.carwash.dispense.AckConfidence
import com.goldsky.carwash.dispense.DispenseJob
import com.goldsky.carwash.dispense.DispenseOutcome
import com.goldsky.carwash.dispense.IAckStrategy
import com.goldsky.carwash.dispense.IDispenseAdapter
import com.goldsky.carwash.payment.ConfigManager
import kotlinx.coroutines.delay

/**
 * Coin-acceptor-style protocol: amount charged is converted into a count of
 * identical pulses (settings.pulse_weight_cents/pulse_hex), one confirmed at
 * a time. This is the car wash relay board's existing protocol, migrated
 * unchanged from MainActivity.startFinalizationSequence.
 */
class PulseCreditAdapter : IDispenseAdapter {
    override suspend fun dispense(job: DispenseJob, ackStrategy: IAckStrategy): DispenseOutcome {
        val settings = ConfigManager.getConfig()?.settings
        val pulseWeight = settings?.pulse_weight_cents?.takeIf { it > 0 } ?: 25
        val pulseHex = settings?.pulse_hex ?: "AA 01 01 55"
        val pulseCount = job.amountCents / pulseWeight

        if (pulseCount <= 0) return DispenseOutcome.Confirmed("no pulses required for ${job.amountCents} cents")

        var sawUnconfirmed = false
        for (i in 1..pulseCount) {
            when (ackStrategy.confirm(pulseHex)) {
                AckConfidence.CONFIRMED -> { /* keep going */ }
                AckConfidence.UNCONFIRMED -> sawUnconfirmed = true
                AckConfidence.FAILED -> return DispenseOutcome.Failed("pulse $i/$pulseCount not delivered")
            }
            delay(200)
        }

        return if (sawUnconfirmed) {
            DispenseOutcome.DeliveredUnconfirmed("$pulseCount pulses sent, board gave no ack")
        } else {
            DispenseOutcome.Confirmed("$pulseCount pulses ack'd")
        }
    }
}
