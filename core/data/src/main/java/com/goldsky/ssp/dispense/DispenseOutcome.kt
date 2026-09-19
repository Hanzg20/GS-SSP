package com.goldsky.ssp.dispense

/**
 * Result of a dispense attempt, split into three states rather than a single
 * success/failure boolean because "the command was sent" and "the hardware
 * confirmed it executed" are genuinely different facts on older relay boards
 * that never ACK: [DeliveredUnconfirmed] must NOT be treated the same as
 * [Failed] by callers (e.g. it should not trigger an automatic VOID/refund --
 * the wash/product most likely did dispense, we just can't prove it).
 */
sealed class DispenseOutcome {
    data class Confirmed(val detail: String? = null) : DispenseOutcome()
    data class DeliveredUnconfirmed(val detail: String? = null) : DispenseOutcome()
    data class Failed(val reason: String) : DispenseOutcome()
}
