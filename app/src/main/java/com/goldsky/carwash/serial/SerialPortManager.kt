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

    // Reply frame: [0xBB][Status][Checksum][0xEE]
    private const val ACK_HEADER: Byte = 0xBB.toByte()
    private const val ACK_FOOTER: Byte = 0xEE.toByte()
    private const val ACK_STATUS_RECEIVED: Byte = 0x00
    private const val ACK_STATUS_EXECUTING: Byte = 0x01
    private const val ACK_STATUS_FAULT: Byte = 0x02
    private const val ACK_TIMEOUT_MS = 500
    private const val ACK_MAX_RETRIES = 3

    private var uart: com.pax.dal.IUart? = null
    private var isOpened = false

    enum class AckResult { OK, FAULT, TIMEOUT, MALFORMED }

    /**
     * Returns true if the UART port is currently opened.
     */
    fun isOpened(): Boolean = isOpened

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
     * Sends a command and waits for the relay board's ACK frame, retrying on
     * timeout/fault up to [ACK_MAX_RETRIES] times. This is what makes hardware
     * failures detectable instead of "fire and forget" — callers use the result
     * to decide whether to trigger a payment VOID.
     */
    suspend fun sendCommandWithAck(
        hexStr: String,
        timeoutMs: Int = ACK_TIMEOUT_MS,
        maxRetries: Int = ACK_MAX_RETRIES
    ): Boolean {
        repeat(maxRetries) { attempt ->
            if (!sendHexString(hexStr)) return@repeat

            when (val result = readAck(timeoutMs)) {
                AckResult.OK -> return true
                AckResult.FAULT -> Log.e(TAG, "Relay reported FAULT on attempt ${attempt + 1}/$maxRetries")
                AckResult.TIMEOUT -> Log.w(TAG, "No ACK within ${timeoutMs}ms on attempt ${attempt + 1}/$maxRetries")
                AckResult.MALFORMED -> Log.w(TAG, "Malformed ACK frame on attempt ${attempt + 1}/$maxRetries: $result")
            }
        }
        Log.e(TAG, "Command '$hexStr' failed after $maxRetries attempts, no valid ACK received")
        return false
    }

    /**
     * Blocking read of a single 4-byte ACK frame: [0xBB][Status][Checksum][0xEE].
     */
    private fun readAck(timeoutMs: Int): AckResult {
        val buffer = ByteArray(4)
        val bytesRead = try {
            uart?.receive(buffer, timeoutMs) ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "UART receive failed: ${e.message}")
            0
        }
        return parseAckFrame(buffer, bytesRead)
    }

    /**
     * Pure frame-validation logic, split out from [readAck] so it's unit
     * testable without a real/mocked IUart. Checksum is validated as
     * XOR(header, status) — confirm against the real relay board protocol
     * doc before production use; this stub protocol isn't formally specified
     * beyond the frame shape.
     */
    internal fun parseAckFrame(buffer: ByteArray, bytesRead: Int): AckResult {
        if (bytesRead < 4) return AckResult.TIMEOUT
        if (buffer[0] != ACK_HEADER || buffer[3] != ACK_FOOTER) return AckResult.MALFORMED

        val status = buffer[1]
        val expectedChecksum = (ACK_HEADER.toInt() xor status.toInt()).toByte()
        if (buffer[2] != expectedChecksum) return AckResult.MALFORMED

        return when (status) {
            ACK_STATUS_RECEIVED, ACK_STATUS_EXECUTING -> AckResult.OK
            ACK_STATUS_FAULT -> AckResult.FAULT
            else -> AckResult.MALFORMED
        }
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
