package com.goldsky.ssp.payment.hardware.wizarpos

import org.junit.Assert.*
import org.junit.Test

class WizarPosP3ProtocolTest {

    @Test
    fun testPackAndUnpack() {
        val jsonPayload = "{\"TransType\":\"Purchase\",\"TransAmount\":\"100\"}"
        val sequence = 123
        
        // 1. Pack
        val frame = WizarPosP3Protocol.pack(WizarPosP3Protocol.CTRL_FROM_CASHIER, sequence, jsonPayload)
        
        // Verify frame structure: STX(1) + VER(1) + CTRL(4) + LEN(2) + JSON(N) + ETX(1) + BCC(1)
        assertEquals(WizarPosP3Protocol.STX, frame[0])
        assertEquals(WizarPosP3Protocol.VERSION, frame[1])
        assertEquals(WizarPosP3Protocol.CTRL_FROM_CASHIER, frame[2])
        // Sequence should be at index 4-5 (Big-Endian)
        assertEquals(0, frame[4].toInt())
        assertEquals(123, frame[5].toInt())
        
        // 2. Unpack
        val unpackedJson = WizarPosP3Protocol.unpack(frame)
        assertEquals(jsonPayload, unpackedJson)
    }

    @Test
    fun testBccValidation() {
        val payload = "TEST"
        val frame = WizarPosP3Protocol.pack(0x01, 1, payload)
        
        // Corrupt BCC
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()
        
        val result = WizarPosP3Protocol.unpack(frame)
        assertNull("Unpack should fail with invalid BCC", result)
    }
}
