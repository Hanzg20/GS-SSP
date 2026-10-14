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

    /**
     * Line settings after [open] (a Modbus relay module is usually 9600 8N1, not the
     * 115200 the wash board uses). [parity]: 0 none, 1 odd, 2 even. False when this
     * provider can't change them.
     */
    fun configure(baud: Int, dataBits: Int = 8, parity: Int = 0, stopBits: Int = 1): Boolean = false

    /**
     * Request / reply on the open port: drops anything unread, writes [request], then
     * reads up to [replyLen] bytes until [timeoutMs]. Null when nothing came back (or
     * this provider can't read); a shorter array when the reply was cut short.
     */
    suspend fun exchange(request: ByteArray, replyLen: Int, timeoutMs: Int): ByteArray? = null
}
