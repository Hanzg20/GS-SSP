package com.goldsky.carwash.serial

import com.goldsky.carwash.payment.hardware.ISerialProvider
import com.goldsky.carwash.payment.hardware.pax.PaxSerialProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SerialPortManagerAckTest {

    private val provider = PaxSerialProvider { null }

    private fun frame(header: Int, status: Int, crcH: Int, crcL: Int, footer: Int) =
        byteArrayOf(header.toByte(), status.toByte(), crcH.toByte(), crcL.toByte(), footer.toByte())

    @Test
    fun `valid RECEIVED frame is OK`() {
        // header=0xBB, status=0x00, CRC16(BB 00) = 0xF72F (approx, let's use actual logic)
        val crc = CrcUtils.crc16ccitt(byteArrayOf(0xBB.toByte(), 0x00.toByte()))
        val buffer = frame(0xBB, 0x00, crc shr 8, crc and 0xFF, 0xEE)
        assertEquals(ISerialProvider.AckResult.OK, provider.parseAckFrame(buffer, 5))
    }

    @Test
    fun `valid EXECUTING frame is OK`() {
        val crc = CrcUtils.crc16ccitt(byteArrayOf(0xBB.toByte(), 0x01.toByte()))
        val buffer = frame(0xBB, 0x01, crc shr 8, crc and 0xFF, 0xEE)
        assertEquals(ISerialProvider.AckResult.OK, provider.parseAckFrame(buffer, 5))
    }

    @Test
    fun `FAULT status is reported as FAULT`() {
        val crc = CrcUtils.crc16ccitt(byteArrayOf(0xBB.toByte(), 0x02.toByte()))
        val buffer = frame(0xBB, 0x02, crc shr 8, crc and 0xFF, 0xEE)
        assertEquals(ISerialProvider.AckResult.FAULT, provider.parseAckFrame(buffer, 5))
    }

    @Test
    fun `fewer than 5 bytes is a TIMEOUT`() {
        val buffer = frame(0xBB, 0x00, 0, 0, 0xEE)
        assertEquals(ISerialProvider.AckResult.TIMEOUT, provider.parseAckFrame(buffer, 4))
    }

    @Test
    fun `wrong header or footer is MALFORMED`() {
        val crc = CrcUtils.crc16ccitt(byteArrayOf(0xBB.toByte(), 0x00.toByte()))
        val wrongHeader = frame(0xAA, 0x00, crc shr 8, crc and 0xFF, 0xEE)
        assertEquals(ISerialProvider.AckResult.MALFORMED, provider.parseAckFrame(wrongHeader, 5))

        val wrongFooter = frame(0xBB, 0x00, crc shr 8, crc and 0xFF, 0x55)
        assertEquals(ISerialProvider.AckResult.MALFORMED, provider.parseAckFrame(wrongFooter, 5))
    }

    @Test
    fun `bad checksum is MALFORMED even with correct header and footer`() {
        val buffer = frame(0xBB, 0x00, 0x00, 0x00, 0xEE)
        assertEquals(ISerialProvider.AckResult.MALFORMED, provider.parseAckFrame(buffer, 5))
    }
}
