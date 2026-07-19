package com.goldsky.carwash.serial

import android.content.Context
import android.util.Log
import com.pax.dal.IDAL
import com.pax.dal.entities.EUartNumber
import com.pax.neptunelite.api.NeptuneLiteUser

/**
 * Singleton manager for RS-232 serial communication with the car wash relay board.
 *
 * Uses the PAX NeptuneLite IDAL (IUart) API for hardware-level serial access.
 * Communicates via UART_1, which maps to /dev/ttyS1 on PAX IM30.
 * Protocol: 9600 baud, 8 data bits, no parity, 1 stop bit.
 *
 * Car wash relay hex commands:
 *   Standard Wash  ($10): AA 01 0A 55  (300s countdown)
 *   Deluxe Wash    ($15): AA 01 0F 55  (450s countdown)
 *   Full Wax       ($20): AA 01 14 55  (600s countdown)
 *   Stop / End         : AA 00 00 55
 */
object SerialPortManager {
    private const val TAG = "SerialPortManager"

    private var uart: com.pax.dal.IUart? = null
    private var isOpened = false

    /**
     * Opens the UART channel using the PAX NeptuneLite SDK.
     * Must be called with a valid Context (e.g., from MainActivity.onCreate).
     */
    fun openPort(context: Context): Boolean {
        if (isOpened) return true
        return try {
            val dal: IDAL = NeptuneLiteUser.getInstance().getDal(context)
            uart = dal.getUart(EUartNumber.UART_1)
            uart?.open()
            // baud=9600, dataBits=8, parity=none(0), stopBits=1, flowControl=none(0)
            uart?.init(9600, 8, 0, 1, 0)
            isOpened = true
            Log.i(TAG, "UART_1 opened via NeptuneLite SDK (9600-8-N-1)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open UART_1: ${e.message}")
            false
        }
    }

    /**
     * Sends a raw byte array to the relay board.
     */
    fun sendBytes(data: ByteArray): Boolean {
        if (!isOpened || uart == null) {
            Log.w(TAG, "UART not open, cannot send")
            return false
        }
        return try {
            uart?.send(data, data.size)
            Log.d(TAG, "Sent HEX: ${data.joinToString(" ") { "%02X".format(it) }}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            false
        }
    }

    /**
     * Sends a hex string (space-separated or continuous) to the relay board.
     * Example: sendHexString("AA 01 0A 55") or sendHexString("AA010A55")
     */
    fun sendHexString(hexStr: String): Boolean {
        val clean = hexStr.replace(" ", "")
        if (clean.length % 2 != 0) {
            Log.e(TAG, "Invalid hex string: $hexStr")
            return false
        }
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return sendBytes(bytes)
    }

    /**
     * Closes the UART channel and releases hardware resources.
     */
    fun closePort() {
        try {
            uart?.close()
            Log.i(TAG, "UART_1 closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing UART: ${e.message}")
        } finally {
            uart = null
            isOpened = false
        }
    }
}
