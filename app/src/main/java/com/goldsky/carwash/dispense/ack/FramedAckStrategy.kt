package com.goldsky.carwash.dispense.ack

import com.goldsky.carwash.dispense.AckConfidence
import com.goldsky.carwash.dispense.IAckStrategy
import com.goldsky.carwash.serial.SerialPortManager

/**
 * For boards that reply with the [0xBB][Status][Checksum][0xEE] frame --
 * behavior unchanged from the original SerialPortManager.sendPulses() call
 * sites this replaces. Never returns UNCONFIRMED: a framed board either ACKs
 * (CONFIRMED) or exhausts its retries (FAILED), there's no in-between state.
 */
object FramedAckStrategy : IAckStrategy {
    override suspend fun confirm(hexStr: String): AckConfidence =
        if (SerialPortManager.sendCommandWithAck(hexStr)) AckConfidence.CONFIRMED else AckConfidence.FAILED
}
