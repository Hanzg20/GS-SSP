package com.goldsky.ssp.payment.hardware.modbus

import com.goldsky.ssp.payment.hardware.ISerialProvider
import kotlinx.coroutines.delay

/**
 * An RS485 Modbus RTU relay module on the terminal's serial port: one terminal driving
 * many machines (Aegis Laundry: 8 washers / dryers), where the Q3mini's own ext board has
 * only 2 relays. Channels are 1-based, as printed on the module.
 *
 * A coin-op machine is started by "coin" pulses on its coin-drop input: [pulse] closes
 * the relay [count] times. Each switch is one write-single-coil, which the module echoes
 * back; switching a coil to the state it's already in changes nothing, so a lost reply
 * is simply sent again. Every ON is followed by an OFF, also when the ON's reply was lost
 * (it may have switched anyway).
 */
class ModbusRelayBoard(
    private val serial: ISerialProvider,
    val slave: Int = 1,
    private val timeoutMs: Int = 300,
    private val tries: Int = 3,
) {
    /**
     * What [pulse] got done. [delivered]: pulses confirmed on and off. [uncertain]: one
     * more may have reached the machine (an ON nobody confirmed). [stuckOn]: the relay
     * could not be confirmed off -- the machine may be seeing a held contact.
     */
    data class PulseResult(
        val requested: Int,
        val delivered: Int,
        val uncertain: Boolean = false,
        val stuckOn: Boolean = false,
        val message: String? = null,
    ) {
        val ok: Boolean get() = delivered == requested && !uncertain && !stuckOn
    }

    /** Relay [channel] on / off; true once the module echoed the command. */
    suspend fun setRelay(channel: Int, on: Boolean, attempts: Int = tries): Boolean {
        require(channel >= 1) { "channel is 1-based" }
        val request = ModbusRtu.writeSingleCoil(slave, channel - 1, on)
        repeat(attempts) {
            val reply = serial.exchange(request, request.size, timeoutMs)
            if (reply != null && reply.contentEquals(request)) return true
        }
        return false
    }

    suspend fun pulse(channel: Int, count: Int, onMs: Long, offMs: Long): PulseResult {
        var delivered = 0
        while (delivered < count) {
            if (!setRelay(channel, true)) {
                // It may have switched with only the reply lost: make sure it's off again.
                val off = setRelay(channel, false, attempts = tries + 3)
                return PulseResult(count, delivered, uncertain = true, stuckOn = !off,
                    message = "Relay $channel did not confirm ON after $delivered of $count pulses")
            }
            delay(onMs)
            if (!setRelay(channel, false, attempts = tries + 3)) {
                return PulseResult(count, delivered, uncertain = true, stuckOn = true,
                    message = "Relay $channel did not confirm OFF after pulse ${delivered + 1} of $count")
            }
            delivered++
            if (delivered < count) delay(offMs)
        }
        return PulseResult(count, delivered)
    }

    /**
     * The module's first [count] inputs (true = closed), e.g. machines' "running"
     * outputs. Null when it didn't answer properly.
     */
    suspend fun readInputs(count: Int): BooleanArray? {
        val request = ModbusRtu.readDiscreteInputs(slave, 0, count)
        repeat(tries) {
            val reply = serial.exchange(request, ModbusRtu.readInputsReplyLen(count), timeoutMs) ?: return@repeat
            if (reply.size != ModbusRtu.readInputsReplyLen(count) || !ModbusRtu.crcOk(reply)) return@repeat
            if ((reply[0].toInt() and 0xFF) != slave || (reply[1].toInt() and 0xFF) != ModbusRtu.FN_READ_DISCRETE_INPUTS) return@repeat
            return BooleanArray(count) { i -> (reply[3 + i / 8].toInt() shr (i % 8)) and 1 == 1 }
        }
        return null
    }
}
