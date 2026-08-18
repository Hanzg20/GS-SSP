package com.goldsky.ssp.payment.hardware.wizarpos

import android.content.Context
import android.util.Log
import com.cloudpos.POSTerminal
import com.cloudpos.serialport.SerialPortDevice
import com.goldsky.ssp.payment.hardware.ISerialProvider
import kotlinx.coroutines.delay

/**
 * WizarPOS Serial Port implementation using CloudPOS SDK.
 */
class WizarPosSerialProvider(private val terminal: POSTerminal?) : ISerialProvider {
    
    companion object {
        private const val TAG = "WizarPosSerial"
    }
    
    private var serialDevice: SerialPortDevice? = null

    override fun open(context: Context): Boolean {
        if (serialDevice != null) return true
        try {
            serialDevice = terminal?.getDevice("com.cloudpos.device.serialport") as? SerialPortDevice
            serialDevice?.open()
            Log.i(TAG, "WizarPOS serial port opened")
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

    override suspend fun sendCommandWithAck(hexStr: String, timeoutMs: Int, maxRetries: Int): Boolean {
        repeat(maxRetries) {
            if (sendHexString(hexStr)) {
                delay(timeoutMs.toLong())
                return true 
            }
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
