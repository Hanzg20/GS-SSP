package com.goldsky.ssp.payment.hardware.modbus

/**
 * Modbus RTU frames for an RS485 relay module (the standard industrial "8-channel Modbus
 * relay" boards): function 05 write single coil (a relay), 02 read discrete inputs (the
 * module's optocoupler inputs, e.g. a machine's "running" signal). Every frame ends with
 * CRC-16/MODBUS, low byte first.
 */
object ModbusRtu {
    const val FN_READ_DISCRETE_INPUTS = 0x02
    const val FN_WRITE_SINGLE_COIL = 0x05

    /** CRC-16/MODBUS (poly 0xA001 reflected, init 0xFFFF) of [data]'s first [len] bytes. */
    fun crc16(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /** [body] with its CRC appended. */
    fun frame(vararg body: Int): ByteArray {
        val out = ByteArray(body.size + 2)
        body.forEachIndexed { i, b -> out[i] = b.toByte() }
        val crc = crc16(out, body.size)
        out[body.size] = (crc and 0xFF).toByte()
        out[body.size + 1] = (crc ushr 8).toByte()
        return out
    }

    /** True when [frame] is long enough and its last two bytes are the CRC of the rest. */
    fun crcOk(frame: ByteArray): Boolean {
        if (frame.size < 4) return false
        val crc = crc16(frame, frame.size - 2)
        return (frame[frame.size - 2].toInt() and 0xFF) == (crc and 0xFF) &&
            (frame[frame.size - 1].toInt() and 0xFF) == (crc ushr 8)
    }

    /** Relay [coil] (0-based address) on or off. The module answers with the same 8 bytes. */
    fun writeSingleCoil(slave: Int, coil: Int, on: Boolean): ByteArray =
        frame(slave, FN_WRITE_SINGLE_COIL, coil ushr 8, coil and 0xFF, if (on) 0xFF else 0x00, 0x00)

    fun readDiscreteInputs(slave: Int, start: Int, count: Int): ByteArray =
        frame(slave, FN_READ_DISCRETE_INPUTS, start ushr 8, start and 0xFF, count ushr 8, count and 0xFF)

    /** Reply length for reading [count] inputs: slave, fn, byte count, data, CRC. */
    fun readInputsReplyLen(count: Int) = 5 + (count + 7) / 8

    /** An exception reply: function code with the high bit set (5 bytes). */
    fun isException(reply: ByteArray, fn: Int) =
        reply.size >= 2 && (reply[1].toInt() and 0xFF) == (fn or 0x80)
}
