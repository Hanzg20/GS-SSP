package com.goldsky.carwash.payment.hardware.pax

import android.content.Context
import android.util.Log
import com.goldsky.carwash.payment.hardware.ISerialProvider
import com.pax.dal.IDAL
import com.pax.dal.IUart
import com.pax.dal.entities.EUartNumber
import kotlinx.coroutines.delay

/**
 * PAX implementation of ISerialProvider using NeptuneLite IUart.
 */
class PaxSerialProvider(private val dalProvider: () -> IDAL?) : ISerialProvider {
    private val TAG = "PaxSerial"

    // Protocol constants from legacy SerialPortManager
    private val ACK_HEADER: Byte = 0xBB.toByte()
    private val ACK_FOOTER: Byte = 0xEE.toByte()
    private val ACK_STATUS_RECEIVED: Byte = 0x00
    private val ACK_STATUS_EXECUTING: Byte = 0x01
    private val ACK_STATUS_FAULT: Byte = 0x02

    private var uart: IUart? = null
    private var isOpened = false

    override fun open(context: Context): Boolean {
        if (isOpened) return true
        val dal = dalProvider()
        if (dal == null) {
            Log.w(TAG, "PAX DAL not available, serial port stays MOCKED/CLOSED")
            return false
        }
        return try {
            uart = dal.getUart(EUartNumber.UART_1)
            uart?.open()
            uart?.init(9600, 8, 0, 1, 0)
            isOpened = true
            Log.i(TAG, "UART_1 opened via NeptuneLite SDK (9600-8-N-1)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open UART_1: ${e.message}")
            false
        }
    }

    override fun close() {
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

    override fun isOpened(): Boolean = isOpened

    override fun sendBytes(data: ByteArray): Boolean {
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

    override fun sendHexString(hexStr: String): Boolean {
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

    override suspend fun sendCommandWithAck(hexStr: String, timeoutMs: Int, maxRetries: Int): Boolean {
        repeat(maxRetries) { attempt ->
            if (!sendHexString(hexStr)) return@repeat

            when (val result = readAck(timeoutMs)) {
                ISerialProvider.AckResult.OK -> return true
                ISerialProvider.AckResult.FAULT -> Log.e(TAG, "Relay reported FAULT on attempt ${attempt + 1}/$maxRetries")
                ISerialProvider.AckResult.TIMEOUT -> Log.w(TAG, "No ACK within ${timeoutMs}ms on attempt ${attempt + 1}/$maxRetries")
                ISerialProvider.AckResult.MALFORMED -> Log.w(TAG, "Malformed ACK frame on attempt ${attempt + 1}/$maxRetries")
            }
            delay(100) // Small breather between retries
        }
        return false
    }

    private fun readAck(timeoutMs: Int): ISerialProvider.AckResult {
        val buffer = ByteArray(4)
        val bytesRead = try {
            uart?.receive(buffer, timeoutMs) ?: 0
        } catch (e: Exception) {
            0
        }
        return parseAckFrame(buffer, bytesRead)
    }

    internal fun parseAckFrame(buffer: ByteArray, bytesRead: Int): ISerialProvider.AckResult {
        if (bytesRead < 4) return ISerialProvider.AckResult.TIMEOUT
        if (buffer[0] != ACK_HEADER || buffer[3] != ACK_FOOTER) return ISerialProvider.AckResult.MALFORMED

        val status = buffer[1]
        val expectedChecksum = (ACK_HEADER.toInt() xor status.toInt()).toByte()
        if (buffer[2] != expectedChecksum) return ISerialProvider.AckResult.MALFORMED

        return when (status) {
            ACK_STATUS_RECEIVED, ACK_STATUS_EXECUTING -> ISerialProvider.AckResult.OK
            ACK_STATUS_FAULT -> ISerialProvider.AckResult.FAULT
            else -> ISerialProvider.AckResult.MALFORMED
        }
    }
}
