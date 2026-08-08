package com.goldsky.carwash.payment.hardware.pax

import android.content.Context
import android.util.Log
import com.goldsky.carwash.payment.hardware.ISerialProvider
import com.goldsky.carwash.serial.CrcUtils
import com.pax.dal.IDAL
import com.pax.dal.IUart
import com.pax.dal.entities.EUartNumber
import kotlinx.coroutines.delay

/**
 * PAX implementation of ISerialProvider using NeptuneLite IUart.
 */
class PaxSerialProvider(private val dalProvider: () -> IDAL?) : ISerialProvider {
    private val TAG = "PaxSerial"

    // Protocol constants upgraded to Industrial Standard (v2.2)
    private val ACK_HEADER: Byte = 0xBB.toByte()
    private val ACK_FOOTER: Byte = 0xEE.toByte()
    private val ACK_STATUS_RECEIVED: Byte = 0x00
    private val ACK_STATUS_EXECUTING: Byte = 0x01
    private val ACK_STATUS_FAULT: Byte = 0x02
    
    // Total 5 bytes: [Header][Status][CRC_H][CRC_L][Footer]
    private val ACK_FRAME_SIZE = 5

    private var uart: IUart? = null
    private var isOpened = false

    override fun open(context: Context): Boolean {
        if (isOpened) return true
        val dal = dalProvider()
        if (dal == null) {
            Log.w(TAG, "PAX DAL not available, serial port switching to MOCK mode")
            isOpened = true
            return true
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
        if (!isOpened) {
            Log.w(TAG, "UART not open, cannot send")
            return false
        }
        if (uart == null) {
            Log.d(TAG, "MOCK SEND HEX: ${data.joinToString(" ") { "%02X".format(it) }}")
            return true
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
        if (dalProvider() == null) {
            Log.d(TAG, "MOCK SEND COMMAND WITH ACK: $hexStr")
            return true
        }
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
        val buffer = ByteArray(ACK_FRAME_SIZE)
        val bytesRead = try {
            uart?.receive(buffer, timeoutMs) ?: 0
        } catch (e: Exception) {
            0
        }
        return parseAckFrame(buffer, bytesRead)
    }

    internal fun parseAckFrame(buffer: ByteArray, bytesRead: Int): ISerialProvider.AckResult {
        if (bytesRead < ACK_FRAME_SIZE) return ISerialProvider.AckResult.TIMEOUT
        if (buffer[0] != ACK_HEADER || buffer[ACK_FRAME_SIZE - 1] != ACK_FOOTER) return ISerialProvider.AckResult.MALFORMED

        val status = buffer[1]
        
        // CRC16-CCITT check on [Header, Status]
        val payload = byteArrayOf(buffer[0], buffer[1])
        val expectedCrc = CrcUtils.crc16ccitt(payload)
        val receivedCrc = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        
        if (receivedCrc != expectedCrc) {
            Log.e(TAG, "CRC Mismatch: expected ${"%04X".format(expectedCrc)}, got ${"%04X".format(receivedCrc)}")
            return ISerialProvider.AckResult.MALFORMED
        }

        return when (status) {
            ACK_STATUS_RECEIVED, ACK_STATUS_EXECUTING -> ISerialProvider.AckResult.OK
            ACK_STATUS_FAULT -> ISerialProvider.AckResult.FAULT
            else -> ISerialProvider.AckResult.MALFORMED
        }
    }
}
