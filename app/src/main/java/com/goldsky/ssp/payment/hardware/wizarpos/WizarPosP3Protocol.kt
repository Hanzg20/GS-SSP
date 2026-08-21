package com.goldsky.ssp.payment.hardware.wizarpos

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Helper to pack and unpack WizarPOS P3 (Core Protocol) binary frames.
 * Frame structure: STX(02) | VERSION(02) | CTRL(4 bytes) | LEN(2 bytes) | PAYLOAD | ETX(03) | BCC
 */
object WizarPosP3Protocol {
    private const val TAG = "WizarPosP3"
    
    const val STX: Byte = 0x02
    const val ETX: Byte = 0x03
    const val VERSION: Byte = 0x02
    
    const val CTRL_FROM_CASHIER: Byte = 0x01
    const val CTRL_FROM_POS: Byte = 0x02
    const val CTRL_HANDSHAKE_REQ: Byte = 0xF1.toByte()
    const val CTRL_HANDSHAKE_RESP: Byte = 0xF2.toByte()

    /**
     * Packs a JSON payload into a P3 frame.
     */
    fun pack(path: Byte, sequence: Int, payload: String): ByteArray {
        val contentBytes = payload.toByteArray(Charsets.UTF_8)
        
        // Size: STX(1) + VER(1) + CTRL(4) + LEN(2) + CONTENT(N) + ETX(1) + BCC(1) = N + 11
        val frameSize = contentBytes.size + 11
        val buffer = ByteBuffer.allocate(frameSize).order(ByteOrder.BIG_ENDIAN)
        
        buffer.put(STX)
        buffer.put(VERSION)
        
        // CTRL[0]=Path, CTRL[1]=0, CTRL[2,3]=Sequence
        buffer.put(path)
        buffer.put(0x00.toByte())
        buffer.putShort(sequence.toShort())
        
        // LEN
        buffer.putShort(contentBytes.size.toShort())
        
        // PAYLOAD
        buffer.put(contentBytes)
        
        // ETX
        buffer.put(ETX)
        
        // BCC (XOR from VERSION to ETX)
        val frame = buffer.array()
        val bcc = calculateBcc(frame, 1, frameSize - 2)
        frame[frameSize - 1] = bcc
        
        return frame
    }

    /**
     * Unpacks a P3 frame and extracts the JSON payload.
     * Validates STX, ETX, and BCC.
     */
    fun unpack(frame: ByteArray): String? {
        if (frame.size < 11) return null
        if (frame[0] != STX) {
            Log.e(TAG, "Invalid STX: ${frame[0]}")
            return null
        }
        
        val contentLen = ByteBuffer.wrap(frame, 6, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        if (frame.size != contentLen + 11) {
            Log.e(TAG, "Frame size mismatch. Expected: ${contentLen + 11}, Actual: ${frame.size}")
            return null
        }
        
        if (frame[contentLen + 9] != ETX) {
            Log.e(TAG, "Invalid ETX: ${frame[contentLen + 9]}")
            return null
        }
        
        val receivedBcc = frame[frame.size - 1]
        val calculatedBcc = calculateBcc(frame, 1, frame.size - 2)
        if (receivedBcc != calculatedBcc) {
            Log.e(TAG, "BCC mismatch. Received: $receivedBcc, Calculated: $calculatedBcc")
            return null
        }
        
        return String(frame, 8, contentLen, Charsets.UTF_8)
    }

    private fun calculateBcc(data: ByteArray, start: Int, end: Int): Byte {
        var bcc: Byte = 0
        for (i in start..end) {
            bcc = (bcc.toInt() xor data[i].toInt()).toByte()
        }
        return bcc
    }
}
