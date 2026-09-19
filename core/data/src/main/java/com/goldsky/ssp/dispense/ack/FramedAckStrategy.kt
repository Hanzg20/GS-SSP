package com.goldsky.ssp.dispense.ack

import com.goldsky.ssp.dispense.AckConfidence
import com.goldsky.ssp.dispense.IAckStrategy
import com.goldsky.ssp.payment.hardware.ISerialProvider

/**
 * For boards that reply with the [0xBB][Status][Checksum][0xEE] frame --
 * behavior unchanged from the original SerialPortManager.sendPulses() call
 * sites this replaces. Never returns UNCONFIRMED: a framed board either ACKs
 * (CONFIRMED) or exhausts its retries (FAILED), there's no in-between state.
 */
object FramedAckStrategy : IAckStrategy {
    override suspend fun confirm(hexStr: String, serialProvider: ISerialProvider): AckConfidence =
        if (serialProvider.sendCommandWithAck(hexStr)) AckConfidence.CONFIRMED else AckConfidence.FAILED
}
