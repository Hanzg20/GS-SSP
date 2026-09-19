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
}
