package com.goldsky.ssp.payment.hardware.pax

import android.util.Log
import com.goldsky.ssp.payment.hardware.IGpioProvider
import pax.util.DigitalIOManager

/**
 * PAX implementation of IGpioProvider using UPTAPI.
 * Controls physical relay outputs on IM30/IM25 terminals.
 */
class PaxGpioProvider : IGpioProvider {
    
    companion object {
        private const val TAG = "PaxGpio"
    }

    private val ioManager: DigitalIOManager by lazy { DigitalIOManager.getInstance() }

    /**
     * Controls Digital Output (Relay).
     * @param port Typically 0 or 1 for DigOut_1/DigOut_2.
     */
    override fun setRelay(port: Int, on: Boolean) {
        try {
            val value = if (on) 1 else 0
            val res = ioManager.digitalIOSet(port, value)
            if (res != 0) {
                Log.e(TAG, "Failed to set GPIO $port to $value (res: $res)")
            } else {
                Log.i(TAG, "PAX GPIO $port set to $value")
            }
        } catch (e: Exception) {
            Log.e(TAG, "GPIO control error: ${e.message}")
        }
    }

    /**
     * Reads Digital Input.
     */
    override fun readInput(port: Int): Int {
        return try {
            ioManager.digitalIOGet(port)
        } catch (e: Exception) {
            Log.e(TAG, "GPIO read error: ${e.message}")
            -1
        }
    }

    override fun release() {
        // DigitalIOManager doesn't usually require explicit release in this version
    }
}
