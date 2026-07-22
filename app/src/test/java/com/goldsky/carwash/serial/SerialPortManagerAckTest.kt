package com.goldsky.carwash.serial

import org.junit.Assert.assertEquals
import org.junit.Test

class SerialPortManagerAckTest {

    private fun frame(header: Int, status: Int, checksum: Int, footer: Int) =
        byteArrayOf(header.toByte(), status.toByte(), checksum.toByte(), footer.toByte())

    @Test
    fun `valid RECEIVED frame is OK`() {
        // header=0xBB, status=0x00, checksum=XOR(0xBB,0x00)=0xBB, footer=0xEE
        val buffer = frame(0xBB, 0x00, 0xBB, 0xEE)
        assertEquals(SerialPortManager.AckResult.OK, SerialPortManager.parseAckFrame(buffer, 4))
    }

    @Test
    fun `valid EXECUTING frame is OK`() {
        // status=0x01, checksum=XOR(0xBB,0x01)=0xBA
        val buffer = frame(0xBB, 0x01, 0xBA, 0xEE)
        assertEquals(SerialPortManager.AckResult.OK, SerialPortManager.parseAckFrame(buffer, 4))
    }

    @Test
    fun `FAULT status is reported as FAULT`() {
        // status=0x02, checksum=XOR(0xBB,0x02)=0xB9
        val buffer = frame(0xBB, 0x02, 0xB9, 0xEE)
        assertEquals(SerialPortManager.AckResult.FAULT, SerialPortManager.parseAckFrame(buffer, 4))
    }

    @Test
    fun `fewer than 4 bytes is a TIMEOUT`() {
        val buffer = frame(0xBB, 0x00, 0xBB, 0xEE)
        assertEquals(SerialPortManager.AckResult.TIMEOUT, SerialPortManager.parseAckFrame(buffer, 0))
        assertEquals(SerialPortManager.AckResult.TIMEOUT, SerialPortManager.parseAckFrame(buffer, 3))
    }

    @Test
    fun `wrong header or footer is MALFORMED`() {
        val wrongHeader = frame(0xAA, 0x00, 0xBB, 0xEE)
        assertEquals(SerialPortManager.AckResult.MALFORMED, SerialPortManager.parseAckFrame(wrongHeader, 4))

        val wrongFooter = frame(0xBB, 0x00, 0xBB, 0x55)
        assertEquals(SerialPortManager.AckResult.MALFORMED, SerialPortManager.parseAckFrame(wrongFooter, 4))
    }

    @Test
    fun `bad checksum is MALFORMED even with correct header and footer`() {
        val buffer = frame(0xBB, 0x00, 0x00, 0xEE) // checksum should be 0xBB, not 0x00
        assertEquals(SerialPortManager.AckResult.MALFORMED, SerialPortManager.parseAckFrame(buffer, 4))
    }

    @Test
    fun `unknown status byte is MALFORMED`() {
        // status=0x7F, checksum=XOR(0xBB,0x7F)=0xC4
        val buffer = frame(0xBB, 0x7F, 0xC4, 0xEE)
        assertEquals(SerialPortManager.AckResult.MALFORMED, SerialPortManager.parseAckFrame(buffer, 4))
    }
}
