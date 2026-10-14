package com.goldsky.ssp.iris.hardware.wizarpos

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

    /**
     * Which port [open] opens (SerialPortDevice.ID_*): ID_SERIAL_EXT2 (6) by default. Set
     * before [open]; which one a given ext board's RS232 is on is checked on the hardware.
     */
    var port: Int = DEFAULT_PORT

    override fun open(context: Context): Boolean {
        if (serialDevice != null) return true
        try {
            serialDevice = terminal?.getDevice("com.cloudpos.device.serialport") as? SerialPortDevice
            serialDevice?.open(port)
            // NB: the SDK takes DATABITS_* / STOPBITS_* codes, so (8, 1) here is not 8N1
            // (8 is no DATABITS_ code, 1 = STOPBITS_2). Left as it was for the wash board;
            // configure() maps them properly.
            serialDevice?.changeSerialPortParams(115200, 8, 0, 1)
            Log.i(TAG, "WizarPOS serial port $port opened at 115200")
            return true
        } catch (e: Throwable) {
            // Throwable, not Exception: confirmed crashing MainActivity.onCreate()
            // on a real WizarPOS Q3mini, 2026-09-18 -- SerialPortDeviceImpl.open()
            // triggers SerialPortMultiInterface's static init, which throws
            // UnsatisfiedLinkError (an Error, not an Exception) when
            // libjni_cloudpos_serial_multi.so isn't present on this unit.
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

    override fun configure(baud: Int, dataBits: Int, parity: Int, stopBits: Int): Boolean {
        val dev = serialDevice ?: return false
        return try {
            val db = when (dataBits) {
                5 -> SerialPortDevice.DATABITS_5
                6 -> SerialPortDevice.DATABITS_6
                7 -> SerialPortDevice.DATABITS_7
                else -> SerialPortDevice.DATABITS_8
            }
            val par = when (parity) {
                1 -> SerialPortDevice.PARITY_ODD
                2 -> SerialPortDevice.PARITY_EVEN
                else -> SerialPortDevice.PARITY_NONE
            }
            val sb = if (stopBits == 2) SerialPortDevice.STOPBITS_2 else SerialPortDevice.STOPBITS_1
            dev.changeSerialPortParams(baud, db, par, sb)
            Log.i(TAG, "Serial port $port set to $baud $dataBits/$parity/$stopBits")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Serial configure failed: ${e.message}")
            false
        }
    }

    override suspend fun exchange(request: ByteArray, replyLen: Int, timeoutMs: Int): ByteArray? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dev = serialDevice ?: return@withContext null
            try {
                // A late reply to an earlier request must not be read as this one's.
                runCatching { dev.waitForRead(256, 1) }
                dev.write(request, 0, request.size)
                val out = java.io.ByteArrayOutputStream()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (out.size() < replyLen) {
                    val left = (deadline - System.currentTimeMillis()).toInt()
                    if (left <= 0) break
                    val r = runCatching { dev.waitForRead(replyLen - out.size(), left) }.getOrNull()
                    val data = r?.data
                    val n = r?.dataLength ?: 0
                    if (data == null || n <= 0) break
                    out.write(data, 0, minOf(n, data.size))
                }
                if (out.size() == 0) null else out.toByteArray()
            } catch (e: Throwable) {
                Log.e(TAG, "Serial exchange failed: ${e.message}")
                null
            }
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
