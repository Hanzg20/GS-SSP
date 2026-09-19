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
}
