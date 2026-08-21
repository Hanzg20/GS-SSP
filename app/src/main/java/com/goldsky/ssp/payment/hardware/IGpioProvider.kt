package com.goldsky.ssp.payment.hardware

/**
 * Common interface for GPIO and Relay control across different hardware vendors.
 */
interface IGpioProvider {
    /**
     * Sets the state of a specific relay/output port.
     * @param port The port index (vendor-specific).
     * @param on True for HIGH/ON, False for LOW/OFF.
     */
    fun setRelay(port: Int, on: Boolean)

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
