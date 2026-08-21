package com.goldsky.ssp.payment.hardware.wizarpos

import android.content.Context
import android.util.Log
import com.cloudpos.POSTerminal
import com.cloudpos.serialport.SerialPortDevice
import com.goldsky.ssp.payment.hardware.ISerialProvider
import kotlinx.coroutines.delay

/**
 * WizarPOS Serial Port implementation using CloudPOS SDK.
 * Optimized with Maggie's "Header-First" double-stage reading practice.
 */
class WizarPosSerialProvider(private val terminal: POSTerminal?) : ISerialProvider {
    
    companion object {
        private const val TAG = "WizarPosSerial"
        private const val DEFAULT_PORT = 6 // ID_SERIAL_EXT2 for Console/Relay Board
    }
    
    private var serialDevice: SerialPortDevice? = null

    override fun open(context: Context): Boolean {
        if (serialDevice != null) return true
        try {
            serialDevice = terminal?.getDevice("com.cloudpos.device.serialport") as? SerialPortDevice
            serialDevice?.open(DEFAULT_PORT)
            serialDevice?.changeSerialPortParams(115200, 8, 0, 1) // 115200, 8N1
            Log.i(TAG, "WizarPOS serial port $DEFAULT_PORT opened at 115200")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WizarPOS serial: ${e.message}")
            return false
        }
    }

    override fun close() {
        try {
            serialDevice?.close()
            serialDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing serial: ${e.message}")
        }
    }

    override fun isOpened(): Boolean = serialDevice != null

    override fun sendBytes(data: ByteArray): Boolean {
        return try {
            serialDevice?.write(data, 0, data.size)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write error: ${e.message}")
            false
        }
    }

    override fun sendHexString(hexStr: String): Boolean {
        val bytes = hexStringToByteArray(hexStr.replace(" ", ""))
        return sendBytes(bytes)
    }

    /**
     * Implements the best practice: Header-Body double-stage read.
     */
    override suspend fun sendCommandWithAck(hexStr: String, timeoutMs: Int, maxRetries: Int): Boolean {
        repeat(maxRetries) {
            if (sendHexString(hexStr)) {
                // Stage 1: Read Header (e.g., first 3 bytes)
                val headerRes = serialDevice?.waitForRead(3, timeoutMs)
                if (headerRes != null && headerRes.data != null && headerRes.data.size == 3) {
                    // Stage 2: Determine body length and read remaining
                    // Assuming 2nd byte contains payload length for this protocol
                    val bodyLen = headerRes.data[1].toInt() and 0xFF
                    if (bodyLen > 0) {
                        val bodyRes = serialDevice?.waitForRead(bodyLen, 200)
                        if (bodyRes != null && bodyRes.data != null) {
                            Log.d(TAG, "Complete ACK received")
                            return true
                        }
                    } else {
                        return true // Header-only success
                    }
                }
            }
            delay(100)
        }
        return false
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
