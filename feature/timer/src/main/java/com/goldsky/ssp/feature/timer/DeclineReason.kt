package com.goldsky.ssp.feature.timer

/**
 * What the customer is told when a sale doesn't complete. The provider's raw
 * text ("Communication Timeout (P3)", "Payment Error: cancelled by user
 * (-139)") is for logs and the transaction row, not a kiosk screen -- seen on
 * the Q3mini self-test, 2026-09-24.
 */
enum class DeclineReason {
    /** Customer backed out, or the reader timed out waiting for a card. */
    CANCELLED,
    /** Payment service/reader not reachable -- "try again" won't help. */
    UNAVAILABLE,
    /** Anything else: issuer decline, host error, ... */
    DECLINED;

    companion object {
        // PAYWizard answers -139 "cancelled by user" both for Back on its card
        // screen and for OPC's 60s no-card timeout (measured 2026-09-24).
        private val CANCEL_CODES = listOf("(-139)", "cancelled by user", "canceled by user")

        fun classify(message: String, isHardwareFault: Boolean): DeclineReason = when {
            isHardwareFault -> UNAVAILABLE
            CANCEL_CODES.any { message.contains(it, ignoreCase = true) } -> CANCELLED
            else -> DECLINED
        }
    }
}
