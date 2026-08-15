package com.goldsky.ssp.dispense

import com.goldsky.ssp.payment.hardware.ISerialProvider

/**
 * How confident we are that a single command was executed by the hardware,
 * independent of which protocol adapter sent it. [FAILED] means the serial
 * write itself didn't go out (or a framed device explicitly reported a
 * fault) -- that's the only case that should drive an automatic VOID/refund.
 */
enum class AckConfidence { CONFIRMED, UNCONFIRMED, FAILED }

/**
 * Sends one already-formed hex command and reports how sure we are it ran.
 * Kept separate from [com.goldsky.ssp.dispense.IDispenseAdapter] so the
 * same protocol (e.g. plain hex-over-serial) can be paired with either a
 * board that ACKs or one that doesn't, without duplicating the adapter.
 */
interface IAckStrategy {
    suspend fun confirm(hexStr: String, serialProvider: ISerialProvider): AckConfidence
}
