package com.goldsky.ssp.payment.hardware.wizarpos

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
            } catch (e: Exception) {
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
    override fun setRelay(port: Int, on: Boolean) {
        if (!ensureOpened()) return
        try {
            if (on) {
                extBoardDevice?.triggerRelayOn(port)
                Log.i(TAG, "Relay $port turned ON")
            } else {
                extBoardDevice?.triggerRelayOff(port)
                Log.i(TAG, "Relay $port turned OFF")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Relay control error: ${e.message}")
        }
    }

    /**
     * Reads Digital Input (DIN).
     */
    override fun readInput(port: Int): Int {
        if (!ensureOpened()) return -1
        return try {
            extBoardDevice?.readDIN(port) ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "DIN read error: ${e.message}")
            -1
        }
    }
    
    override fun release() {
        try {
            extBoardDevice?.close()
            extBoardDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ExtBoard: ${e.message}")
        }
    }
}
