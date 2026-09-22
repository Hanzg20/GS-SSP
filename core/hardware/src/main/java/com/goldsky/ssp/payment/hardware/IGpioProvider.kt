package com.goldsky.ssp.payment.hardware

/**
 * Common interface for GPIO and Relay control across different hardware vendors.
 */
interface IGpioProvider {
    /**
     * Sets the state of a specific relay/output port.
     * @param port The port index (vendor-specific).
     * @param on True for HIGH/ON, False for LOW/OFF.
     * @return true if the command actually reached the relay hardware, false
     * on any failure (device unopened, native SDK fault, etc). Callers that
     * charge money before pulsing (see DigitIoAdapter) must treat false as a
     * real hardware failure -- never assume the pulse happened just because
     * this didn't throw. Previously returned Unit, which meant a provider
     * that fails open (e.g. WizarPosGpioProvider degrading a missing native
     * lib to a caught no-op) was indistinguishable from a real pulse, and
     * DigitIoAdapter reported DispenseOutcome.Confirmed for a wash that never
     * actually ran -- confirmed on a real WizarPOS Q3mini, 2026-09-18.
     */
    fun setRelay(port: Int, on: Boolean): Boolean

    /**
     * Reads the current state of a digital input port.
     * @param port The input port index.
     * @return 1 for HIGH, 0 for LOW, -1 for error.
     */
    fun readInput(port: Int): Int

    /**
     * Releases GPIO resources.
     */
    fun release()

    /**
     * Fires a single hardware-timed ON/OFF relay pulse (port ON for [onMs],
     * then OFF for [offMs]) -- one "credit" in a coin-acceptor-style pulse
     * train (see DigitIoAdapter). Prefer this over a manual
     * setRelay(true)+delay(onMs)+setRelay(false)+delay(offMs) sequence
     * wherever the vendor SDK offers native timing: kotlinx.coroutines.delay()
     * on the app side is subject to coroutine-dispatcher/GC jitter a
     * native/firmware-timed call isn't, and this is money-driving output
     * (2026-09-19's IS_MOCK incident showed this exact path silently costing
     * real revenue when it misbehaves -- see docs/system_architecture.md).
     *
     * Default implementation is the old software-timed sequence, for
     * providers with no native pulse-train primitive (PaxGpioProvider,
     * MockGpioProvider) -- override where a native one exists (see
     * WizarPosGpioProvider, backed by ExtBoardDevice.triggerRelay).
     *
     * @return true if the pulse was accepted by the hardware (same
     * fail-closed contract as [setRelay] -- never assume the pulse happened
     * just because this didn't throw).
     */
    suspend fun triggerRelayPulse(port: Int, onMs: Long, offMs: Long): Boolean {
        if (!setRelay(port, true)) return false
        kotlinx.coroutines.delay(onMs)
        setRelay(port, false)
        kotlinx.coroutines.delay(offMs)
        return true
    }
}
