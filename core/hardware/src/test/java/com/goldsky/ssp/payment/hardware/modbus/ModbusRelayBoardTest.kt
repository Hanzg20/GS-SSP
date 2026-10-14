package com.goldsky.ssp.payment.hardware.modbus

import android.content.Context
import com.goldsky.ssp.payment.hardware.ISerialProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModbusRelayBoardTest {

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /** A Modbus relay module: keeps its coils, counts closed->open pulses, can lose replies. */
    private class FakeModule(val slave: Int = 1) : ISerialProvider {
        val coils = BooleanArray(8)
        val pulses = IntArray(8)
        val inputs = BooleanArray(8)
        var calls = 0
        /** Calls (1-based) whose command is carried out but whose reply is lost. */
        var loseReplyOn = setOf<Int>()
        /** Calls that never reach the module at all. */
        var dropOn = setOf<Int>()
        var dead = false

        override fun open(context: Context) = true
        override fun close() {}
        override fun isOpened() = true
        override fun sendBytes(data: ByteArray) = true
        override fun sendHexString(hexStr: String) = true
        override suspend fun sendCommandWithAck(hexStr: String, timeoutMs: Int, maxRetries: Int) = true

        override suspend fun exchange(request: ByteArray, replyLen: Int, timeoutMs: Int): ByteArray? {
            calls++
            if (dead || calls in dropOn || !ModbusRtu.crcOk(request) || request[0].toInt() != slave) return null
            val reply = when (request[1].toInt()) {
                ModbusRtu.FN_WRITE_SINGLE_COIL -> {
                    val coil = request[3].toInt()
                    val on = (request[4].toInt() and 0xFF) == 0xFF
                    if (coils[coil] && !on) pulses[coil]++
                    coils[coil] = on
                    request.copyOf()
                }
                ModbusRtu.FN_READ_DISCRETE_INPUTS -> {
                    var bits = 0
                    for (i in 0 until 8) if (inputs[i]) bits = bits or (1 shl i)
                    ModbusRtu.frame(slave, 0x02, 1, bits)
                }
                else -> return null
            }
            return if (calls in loseReplyOn) null else reply
        }
    }

    @Test
    fun `frames match the Modbus reference examples`() {
        assertArrayEquals(hex("01 05 00 00 FF 00 8C 3A"), ModbusRtu.writeSingleCoil(1, 0, true))
        assertArrayEquals(hex("01 05 00 00 00 00 CD CA"), ModbusRtu.writeSingleCoil(1, 0, false))
        assertArrayEquals(hex("01 02 00 00 00 08 79 CC"), ModbusRtu.readDiscreteInputs(1, 0, 8))
        assertTrue(ModbusRtu.crcOk(hex("01 05 00 00 FF 00 8C 3A")))
        assertFalse(ModbusRtu.crcOk(hex("01 05 00 00 FF 00 8C 3B")))
    }

    @Test
    fun `pulses reach the right relay and it ends off`() = runBlocking {
        val m = FakeModule()
        val r = ModbusRelayBoard(m).pulse(channel = 5, count = 12, onMs = 1, offMs = 1)
        assertTrue(r.ok)
        assertEquals(12, r.delivered)
        assertEquals(12, m.pulses[4])
        assertEquals(0, m.pulses.filterIndexed { i, _ -> i != 4 }.sum())
        assertFalse(m.coils[4])
    }

    @Test
    fun `a lost reply is sent again without an extra pulse`() = runBlocking {
        val m = FakeModule().apply { loseReplyOn = setOf(1, 4) } // first ON's reply, second OFF's reply
        val r = ModbusRelayBoard(m).pulse(1, 3, 1, 1)
        assertTrue(r.ok)
        assertEquals(3, m.pulses[0])
        assertFalse(m.coils[0])
    }

    @Test
    fun `an ON that may have happened is switched off and reported uncertain`() = runBlocking {
        // The 2nd pulse's ON is carried out three times but no reply ever comes back.
        val m = FakeModule().apply { loseReplyOn = setOf(3, 4, 5) }
        val r = ModbusRelayBoard(m).pulse(1, 4, 1, 1)
        assertFalse(r.ok)
        assertEquals(1, r.delivered)
        assertTrue(r.uncertain)
        assertFalse(r.stuckOn)
        assertFalse("relay must be off again", m.coils[0])
        assertEquals(2, m.pulses[0]) // the machine did get the uncertain one
    }

    @Test
    fun `a module that stops answering mid-pulse is reported stuck on`() = runBlocking {
        val m = FakeModule()
        val board = ModbusRelayBoard(m)
        assertTrue(board.setRelay(3, true))
        m.dead = true
        assertFalse(board.setRelay(3, false))
        val r = ModbusRelayBoard(FakeModule().apply { dropOn = (2..20).toSet() }).pulse(1, 2, 1, 1)
        assertTrue(r.stuckOn)
        assertEquals(0, r.delivered)
    }

    @Test
    fun `inputs are read bit by bit`() = runBlocking {
        val m = FakeModule().apply { inputs[0] = true; inputs[6] = true }
        val inputs = ModbusRelayBoard(m).readInputs(8)!!
        assertTrue(inputs[0]); assertTrue(inputs[6]); assertFalse(inputs[1])
        assertNull(ModbusRelayBoard(FakeModule().apply { dead = true }).readInputs(8))
    }
}
