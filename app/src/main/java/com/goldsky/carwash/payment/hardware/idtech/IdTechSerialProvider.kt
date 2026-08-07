package com.goldsky.carwash.payment.hardware.idtech

import android.content.Context
import android.util.Log
import com.goldsky.carwash.payment.hardware.ISerialProvider

/**
 * ID TECH implementation of ISerialProvider.
 * Currently serves as a transparent mock/proxy as ID TECH hardware
 * typically doesn't manage external RS-232 relay boards directly.
 */
class IdTechSerialProvider : ISerialProvider {
    private val TAG = "IdTechSerial"
    private var isOpened = false

    override fun open(context: Context): Boolean {
        isOpened = true
        Log.i(TAG, "ID TECH Serial Mock OPENED")
        return true
    }

    override fun close() {
        isOpened = false
        Log.i(TAG, "ID TECH Serial Mock CLOSED")
    }

    override fun isOpened(): Boolean = isOpened

    override fun sendBytes(data: ByteArray): Boolean {
        Log.d(TAG, "MOCK SEND BYTES: ${data.joinToString(" ") { "%02X".format(it) }}")
        return true
    }

    override fun sendHexString(hexStr: String): Boolean {
        Log.d(TAG, "MOCK SEND HEX: $hexStr")
        return true
    }

    override suspend fun sendCommandWithAck(hexStr: String, timeoutMs: Int, maxRetries: Int): Boolean {
        Log.d(TAG, "MOCK SEND COMMAND WITH ACK: $hexStr")
        return true
    }
}
