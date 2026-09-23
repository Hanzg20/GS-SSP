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

    /**
     * Fires a single hardware-timed pulse on the **Pulse** circuit -- NOT the
     * same physical circuit [triggerRelayPulse] drives. Per
     * docs/wizarpos_upt_integration_spec.md §1.1 (real 10-pin measurement,
     * 2026-09-22): Relay (PIN6/7, DC-/DC+) energizes an external relay's
     * COIL; Pulse (PIN1/2/8) is a direct 12V logic-level signal output, the
     * electrically correct one for signaling a downstream pulse-counting
     * board (coin-acceptor-style credit timer) -- confirmed 2026-09-22 by
     * the wash site's actual wiring: the downstream board's input is on
     * PIN1 (`port=0`), not the relay coil terminals.
     *
     * @param voltage 0 or 1, per the vendor's documented contract: idle
     * level is the OPPOSITE of [voltage] (voltage=0 => idle HIGH/pulse LOW;
     * voltage=1 => idle LOW/pulse HIGH). Must be derived from a real
     * multimeter reading of the port's idle level, never guessed -- on this
     * wash site PIN1 measured 12V (HIGH) at idle, which is `voltage=0`.
     * Every call re-applies `setPulseVoltage` before triggering so voltage
     * and idle state can't silently drift apart (the vendor's own docs
     * require the two to always match).
     *
     * Default implementation has no real Pulse-circuit primitive to fall
     * back on (`PaxGpioProvider`/`MockGpioProvider` have no notion of a
     * separate logic-pulse output distinct from a relay toggle) -- best
     * effort via the generic on/off toggle, ignoring polarity. Override
     * where a real one exists (see WizarPosGpioProvider, backed by
     * `ExtBoardDevice.setPulseVoltage`/`triggerPulse`).
     *
     * @return true if the pulse was accepted by the hardware (same
     * fail-closed contract as [setRelay]/[triggerRelayPulse]).
     */
    suspend fun triggerLogicPulse(port: Int, voltage: Int, onMs: Long, offMs: Long): Boolean {
        if (!setRelay(port, true)) return false
        kotlinx.coroutines.delay(onMs)
        setRelay(port, false)
        kotlinx.coroutines.delay(offMs)
        return true
    }

    /**
     * Holds the **Relay** circuit (PIN6/7) ON for [durationMs] -- the
     * "terminal times the session, output stays conducting" dispense mode
     * (Aegis Timer products, e.g. a self-service vacuum: $2 = 4 min, $3 = 5 min), as opposed to the
     * credit-pulse-train mode [triggerLogicPulse] serves for wash.
     *
     * May block for up to [durationMs] depending on the vendor's native call
     * (DigitIoAdapter's per-pulse loop has no delay() yet produces discrete
     * pulses, which suggests WizarPOS's triggerPulse/triggerRelay block until
     * done) -- always call off the main thread, and drive any on-screen
     * countdown from the caller's own clock, not from this returning.
     * Where the vendor can hardware-time the hold (see WizarPosGpioProvider,
     * `ExtBoardDevice.triggerRelay(port, durationMs, ..., 1)`), the board
     * switches OFF by itself even if this app crashes or is killed mid-session
     * -- the fail-safe that matters here, since a stuck-ON output is free
     * service until someone notices. This default has no such primitive and
     * just turns the relay ON: the caller must ALWAYS call [releaseHold] when
     * its own countdown ends (do so regardless of provider, as a second
     * safeguard behind the hardware timer).
     *
     * @return true if the hardware accepted the command (same fail-closed
     * contract as [setRelay]).
     */
    suspend fun holdRelayOutput(port: Int, durationMs: Long): Boolean = setRelay(port, true)

    /**
     * Ends a [holdRelayOutput] session (early stop, or the caller's own
     * end-of-countdown safeguard). Safe to call when nothing is held.
     */
    fun releaseHold(port: Int): Boolean = setRelay(port, false)
}
