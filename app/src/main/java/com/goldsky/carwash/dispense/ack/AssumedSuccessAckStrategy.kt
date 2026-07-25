package com.goldsky.carwash.dispense.ack

import com.goldsky.carwash.dispense.AckConfidence
import com.goldsky.carwash.dispense.IAckStrategy
import com.goldsky.carwash.serial.SerialPortManager
import kotlinx.coroutines.delay

/**
 * For older boards that never reply. The only thing this can actually
 * verify is that the write left the serial port -- it waits [settleDelayMs]
 * to give the board time to act on it, then reports UNCONFIRMED, never
 * CONFIRMED. Whether the command really executed is a gap this strategy
 * cannot close; that's a deliberate signal to callers (see [DispenseOutcome]),
 * not a bug -- close it with an external sensor/camera/manual audit if one
 * becomes available, not by pretending this is CONFIRMED.
 */
class AssumedSuccessAckStrategy(private val settleDelayMs: Long = 800) : IAckStrategy {
    override suspend fun confirm(hexStr: String): AckConfidence {
        val sent = SerialPortManager.sendHexString(hexStr)
        if (!sent) return AckConfidence.FAILED
        delay(settleDelayMs)
        return AckConfidence.UNCONFIRMED
    }
}
