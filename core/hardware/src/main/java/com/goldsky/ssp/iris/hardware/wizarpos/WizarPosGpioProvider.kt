package com.goldsky.ssp.iris.hardware.wizarpos

import android.util.Log
import com.cloudpos.POSTerminal
import com.cloudpos.extboard.ExtBoardDevice
import com.goldsky.ssp.payment.hardware.IGpioProvider

/**
 * WizarPOS GPIO / Relay Provider implementation.
 * Directly controls Digit IO ports on Q3mini UPT.
 */
class WizarPosGpioProvider(private val terminal: POSTerminal?) : IGpioProvider {
    
    companion object {
        private const val TAG = "WizarPosGpio"
    }
    
    private var extBoardDevice: ExtBoardDevice? = null

    private fun ensureOpened(): Boolean {
        if (extBoardDevice == null) {
            try {
                extBoardDevice = terminal?.getDevice("com.cloudpos.device.extboard") as? ExtBoardDevice
                extBoardDevice?.open()
                Log.i(TAG, "ExtBoard device opened for GPIO control")
            } catch (e: Throwable) {
                // Throwable, not Exception: confirmed crashing the whole app
                // mid-payment on a real WizarPOS Q3mini, 2026-09-18 --
                // ExtBoardDeviceImpl.open() triggers ExtBoardInterface's
                // static init, which throws UnsatisfiedLinkError (an Error,
                // not an Exception) when libjni_cloudpos_ext_board.so isn't
                // present on this unit. DispenseEngine must be able to reach
                // its own hardware-failure/auto-void path (see MainActivity's
                // startFinalizationSequence) instead of the app dying with the
                // payment already recorded PAID and nothing dispensed.
                Log.e(TAG, "Failed to open ExtBoard device: ${e.message}")
                return false
            }
        }
        return extBoardDevice != null
    }

    /**
     * Andy's Scheme: Directly trigger relay on Digit IO port.
     * @param port 0 or 1 for Q3mini 2-way relay
     */
    override fun setRelay(port: Int, on: Boolean): Boolean {
        if (!ensureOpened()) return false
        return try {
            if (on) {
                extBoardDevice?.triggerRelayOn(port)
                Log.i(TAG, "Relay $port turned ON")
            } else {
                extBoardDevice?.triggerRelayOff(port)
                Log.i(TAG, "Relay $port turned OFF")
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Relay control error: ${e.message}")
            false
        }
    }

    /**
     * Reads Digital Input (DIN).
     */
    override fun readInput(port: Int): Int {
        if (!ensureOpened()) return -1
        return try {
            extBoardDevice?.readDIN(port) ?: -1
        } catch (e: Throwable) {
            Log.e(TAG, "DIN read error: ${e.message}")
            -1
        }
    }

    override fun release() {
        try {
            extBoardDevice?.close()
            extBoardDevice = null
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing ExtBoard: ${e.message}")
        }
    }

    /**
     * Native hardware-timed override, per the real Digit IO pin map
     * (docs/wizarpos_upt_integration_spec.md §1.1): this drives the same
     * RELAY_DC-/DC+ circuit (PIN6/7) as [setRelay]/`triggerRelayOn/Off`,
     * just via `ExtBoardDevice.triggerRelay(portNum, onMs, offMs, times)`
     * (confirmed present on the bundled SDK via javap, matches WizarPOS's
     * own official APIDemoForAar usage `triggerRelay(0, 500, 500, 5)`) --
     * a single call the native/firmware layer times, instead of this class's
     * setRelay(true)+delay+setRelay(false)+delay loop from the app side.
     * `times=1`: DigitIoAdapter still calls this once per credit so its
     * existing per-pulse onProgress callback and per-pulse failure handling
     * (requireAck/assumed_success) are unchanged -- only the ON/OFF timing
     * for a single pulse moves from app-side delay() to native timing.
     */
    override suspend fun triggerRelayPulse(port: Int, onMs: Long, offMs: Long): Boolean {
        if (!ensureOpened()) return false
        return try {
            extBoardDevice?.triggerRelay(port, onMs.toInt(), offMs.toInt(), 1)
            Log.i(TAG, "Native relay pulse on port $port (${onMs}ms/${offMs}ms)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "triggerRelay error: ${e.message}")
            false
        }
    }

    /**
     * Native hardware-timed override for the **Pulse** circuit (PIN1/2/8,
     * NOT the same physical circuit [triggerRelayPulse] drives -- see
     * docs/wizarpos_upt_integration_spec.md §1.1). Uses
     * `ExtBoardDevice.setPulseVoltage(voltage)` + `.triggerPulse(portNum,
     * voltage, onMs, offMs, times=1)`, both confirmed present on the bundled
     * SDK via javap. `setPulseVoltage` is called every time, not once at
     * init: it's a device-wide (not per-port) setting per its single-int
     * signature, and the vendor's own docs require it to always match the
     * `voltage` passed to `triggerPulse` -- re-applying it here means the
     * two can never silently drift apart, at the cost of one extra JNI call
     * per pulse (cheap relative to the 500ms+ pulse itself).
     */
    override suspend fun triggerLogicPulse(port: Int, voltage: Int, onMs: Long, offMs: Long): Boolean {
        if (!ensureOpened()) return false
        return try {
            extBoardDevice?.setPulseVoltage(voltage)
            extBoardDevice?.triggerPulse(port, voltage, onMs.toInt(), offMs.toInt(), 1)
            Log.i(TAG, "Native logic pulse on port $port (voltage=$voltage, ${onMs}ms/${offMs}ms)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "triggerPulse error: ${e.message}")
            false
        }
    }

    /**
     * Hardware-timed hold: a single `triggerRelay(port, durationMs, offMs, 1)`
     * so the ext-board MCU switches the relay OFF by itself -- survives an
     * app crash/kill mid-session, unlike triggerRelayOn + app-side timer.
     *
     * NOT YET VERIFIED on real hardware (2026-09-23): the SDK's onMs is a
     * plain int with no documented upper bound, and the vendor's own demo
     * only ever uses 500ms. If the MCU stores it in 16 bits, anything above
     * 65535ms (~65s) silently wraps/truncates and the output drops early --
     * this is exactly what feature:timer's HoldTestScreen exists to measure. The
     * native call's own return latency is logged too (does it block for the
     * whole duration?), and whether [releaseHold]'s triggerRelayOff can cut
     * a hardware-timed hold short is the other open question it answers.
     */
    override suspend fun holdRelayOutput(port: Int, durationMs: Long): Boolean {
        if (!ensureOpened()) return false
        if (durationMs <= 0 || durationMs > Int.MAX_VALUE) {
            Log.e(TAG, "holdRelayOutput: durationMs out of range: $durationMs")
            return false
        }
        return try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            extBoardDevice?.triggerRelay(port, durationMs.toInt(), HOLD_TRAILING_OFF_MS, 1)
            val took = android.os.SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "Native relay HOLD on port $port for ${durationMs}ms (native call returned after ${took}ms)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "triggerRelay (hold) error: ${e.message}")
            false
        }
    }

    override fun releaseHold(port: Int): Boolean = setRelay(port, false)
}

// times=1, so the trailing OFF interval after the single ON phase has no
// visible effect; kept small but non-zero in case the firmware rejects 0.
private const val HOLD_TRAILING_OFF_MS = 100
