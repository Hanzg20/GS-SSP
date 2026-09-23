package com.goldsky.ssp.feature.timer

import com.goldsky.ssp.payment.hardware.IGpioProvider

/**
 * What a paid Timer session switches on. Kept behind an interface so the
 * technician "demo mode" can run the whole customer UI without touching the
 * machine, and so the physical circuit can change once the on-site hold test
 * (HoldTestScreen) settles which line of the coin-acceptor harness we're on.
 */
interface TimerOutput {
    /**
     * Switches the output on for [durationMs]. May block for the whole
     * duration on some vendors (see IGpioProvider.holdRelayOutput) -- call off
     * the main thread. Returns false only on a definite hardware rejection.
     */
    suspend fun start(durationMs: Long): Boolean

    /** End-of-session / recovery safeguard. Safe to call when already off. */
    fun stop()
}

/**
 * Relay circuit (PIN6/7), hardware-timed where the vendor supports it.
 * Pending the on-site test: if the harness turns out to be the coin SIGNAL
 * line, Timer products are really pulse products and this class is the one
 * piece to swap.
 */
class RelayHoldOutput(private val gpio: IGpioProvider, private val port: Int = 0) : TimerOutput {
    override suspend fun start(durationMs: Long) = gpio.holdRelayOutput(port, durationMs)
    override fun stop() { gpio.releaseHold(port) }
}

/** Technician demo mode: runs the customer flow end to end, drives nothing. */
object DemoOutput : TimerOutput {
    override suspend fun start(durationMs: Long) = true
    override fun stop() {}
}
