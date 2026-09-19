package com.goldsky.ssp.payment.hardware

import android.content.Context

/**
 * Common interface for serial communication with external peripherals (e.g. relay boards).
 */
interface ISerialProvider {

    enum class AckResult { OK, FAULT, TIMEOUT, MALFORMED }

    /**
     * Opens the serial port.
     */
    fun open(context: Context): Boolean

    /**
     * Closes the serial port.
     */
    fun close()

    /**
     * Checks if the port is currently opened.
     */
    fun isOpened(): Boolean

    /**
     * Sends a raw byte array.
     */
    fun sendBytes(data: ByteArray): Boolean

    /**
     * Sends a HEX string.
     */
    fun sendHexString(hexStr: String): Boolean

    /**
     * Sends a command and waits for an ACK frame with retries.
     */
    suspend fun sendCommandWithAck(
        hexStr: String,
        timeoutMs: Int = 500,
        maxRetries: Int = 3
    ): Boolean
}
