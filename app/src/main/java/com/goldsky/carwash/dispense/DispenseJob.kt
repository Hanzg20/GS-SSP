package com.goldsky.carwash.dispense

/**
 * [startHex] is whatever command string the caller already resolved for this
 * purchase (product.attributes["serial_hex"] for package purchases, a fixed
 * default for custom-amount purchases) -- adapters that only care about a
 * money->pulse-count ratio (e.g. [com.goldsky.carwash.dispense.adapter.PulseCreditAdapter])
 * can ignore it.
 */
data class DispenseJob(
    val amountCents: Int,
    val startHex: String,
    val deviceSn: String,
    val txRef: String
)
