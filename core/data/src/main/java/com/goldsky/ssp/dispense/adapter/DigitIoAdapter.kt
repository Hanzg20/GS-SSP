package com.goldsky.ssp.dispense.adapter

import com.goldsky.ssp.dispense.*
import com.goldsky.ssp.dispense.ack.AssumedSuccessAckStrategy
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.hardware.IGpioProvider
import com.goldsky.ssp.payment.hardware.ISerialProvider
import kotlinx.coroutines.delay

/**
 * Andy's Scheme: Directly trigger the hardware relay via GPIO (Digit IO),
 * bypassing the serial port entirely. Used for Wash on Q3mini/IM30 UPT
 * machines whose pump/valve relay is wired to Digit IO rather than the
 * Console (serial) port.
 *
 * The wash relay/timer board is a coin-acceptor-style device: it counts
 * discrete ON/OFF pulses as credits (same convention [PulseCreditAdapter]
 * already uses over serial), it does not read a raw sustained-hold
 * duration. Pulse count is derived from the charged amount the same way
 * [PulseCreditAdapter] does (settings.pulse_weight_cents), and the 500ms
 * ON / 500ms OFF timing matches the field-tested, hardware-validated
 * constants in gs-EdgeNexus's relay_driver.h (RELAY_PULSE_WIDTH_MS /
 * RELAY_PULSE_INTERVAL_MS -- "Nayax-optimized ... DO NOT CHANGE") for the
 * same class of relay board driven via direct GPIO there.
 *
 * [gpioProvider] must be resolved by the caller (DispenseEngine, via
 * HardwareFactory.getGpioProvider(context, vendor) using a real Activity
 * Context) and injected in -- this adapter has no Context of its own to
 * resolve one itself.
 */
class DigitIoAdapter : IDispenseAdapter {
    private val TAG = "DigitIoAdapter"

    companion object {
        private const val PULSE_WIDTH_MS = 500L
        private const val PULSE_INTERVAL_MS = 500L
    }

    override suspend fun dispense(
        job: DispenseJob,
        ackStrategy: IAckStrategy,
        serialProvider: ISerialProvider,
        gpioProvider: IGpioProvider?,
        onProgress: (Int, Int) -> Unit
    ): DispenseOutcome {
        if (gpioProvider == null) {
            android.util.Log.e(TAG, "No GpioProvider injected -- caller must resolve one via HardwareFactory.getGpioProvider()")
            return DispenseOutcome.Failed("gpio_provider_unavailable")
        }

        val settings = ConfigManager.getConfig()?.settings
        val pulseWeight = settings?.pulse_weight_cents?.takeIf { it > 0 } ?: 25
        val pulseCount = job.amountCents / pulseWeight

        if (pulseCount <= 0) return DispenseOutcome.Confirmed("no pulses required for ${job.amountCents} cents")

        // Whether an unacknowledged/rejected pulse should fail (and trigger
        // MainActivity's auto-void/refund) or just be logged and treated as
        // sent-therefore-done, is cloud-configurable via
        // KioskSettings.dispense_ack_mode -- the same switch PulseCreditAdapter's
        // serial path already offers via AssumedSuccessAckStrategy vs
        // FramedAckStrategy, reused here rather than adding a second field.
        // "assumed_success" exists precisely for sites like this one where the
        // relay board (or, on this real WizarPOS Q3mini, the CloudPOS ext-board
        // native library) has no way to report back whether a pulse actually
        // ran -- confirmed on-site 2026-09-18. Default (anything else,
        // including "framed_ack") stays strict: a rejected pulse fails and
        // refunds, since that's the safe default for boards that CAN ack.
        val requireAck = ackStrategy !is AssumedSuccessAckStrategy
        android.util.Log.i(TAG, "Initiating GPIO pulse train: $pulseCount pulses (${PULSE_WIDTH_MS}ms ON / ${PULSE_INTERVAL_MS}ms OFF) for ${job.amountCents} cents, requireAck=$requireAck")
        onProgress(0, pulseCount)
        var anyPulseUnconfirmed = false
        return try {
            for (i in 1..pulseCount) {
                // setRelay returning false means the pulse never actually
                // reached the relay (device unopened, native SDK fault) --
                // it does NOT throw, so this must be checked explicitly.
                if (!gpioProvider.setRelay(0, true)) {
                    if (requireAck) {
                        android.util.Log.e(TAG, "GPIO relay ON command rejected by hardware at pulse $i/$pulseCount")
                        try { gpioProvider.setRelay(0, false) } catch (_: Exception) {}
                        return DispenseOutcome.Failed("GPIO relay command rejected at pulse $i/$pulseCount")
                    }
                    android.util.Log.w(TAG, "GPIO relay ON command rejected at pulse $i/$pulseCount, continuing (assumed_success mode)")
                    anyPulseUnconfirmed = true
                }
                delay(PULSE_WIDTH_MS)
                gpioProvider.setRelay(0, false)
                delay(PULSE_INTERVAL_MS)
                onProgress(i, pulseCount)
            }
            if (anyPulseUnconfirmed) {
                DispenseOutcome.DeliveredUnconfirmed("$pulseCount relay pulses attempted, hardware never confirmed receipt")
            } else {
                DispenseOutcome.Confirmed("$pulseCount relay pulses sent")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "GPIO hardware fault: ${e.message}")
            // Best-effort: make sure the relay doesn't stay stuck ON if the
            // fault happened mid-cycle.
            try { gpioProvider.setRelay(0, false) } catch (_: Exception) {}
            DispenseOutcome.Failed("GPIO fault: ${e.message}")
        }
    }
}
