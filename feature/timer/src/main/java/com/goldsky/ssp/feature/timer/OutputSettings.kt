package com.goldsky.ssp.feature.timer

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.IGpioProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How this terminal drives the machine it's wired to. Set by the technician
 * on site (TechScreen), stored on the terminal -- the wiring belongs to the
 * terminal, not to the merchant's cloud config.
 */
enum class OutputMode(val label: String, val hint: String) {
    RELAY_HOLD("继电器保持 PIN6/7", "继电器在整个时长内闭合（接设备电源/使能线）"),
    LEVEL_HOLD("电平保持 PIN1", "PIN1 在整个时长内保持有效电平；硬件计时，中途无法提前关断"),
    COIN_PULSES("投币脉冲 PIN1", "按金额发脉冲，模拟投币器（设备自己计时）"),
}

data class OutputSettings(
    val mode: OutputMode = OutputMode.RELAY_HOLD,
    val port: Int = 0,
    /** PIN1/2 idle level, same meaning as the output test's voltage (0 = idle high). */
    val voltage: Int = 0,
    /** COIN_PULSES: value of one pulse; $2 at 100 = 2 pulses, at 25 = 8. */
    val centsPerPulse: Int = 100,
    /** COIN_PULSES: pulse ON time, and the same OFF gap between pulses. */
    val pulseWidthMs: Long = 100,
) {
    fun pulsesFor(priceCents: Int): Int = (priceCents / centsPerPulse.coerceAtLeast(1)).coerceAtLeast(1)
}

/**
 * One unit of a dual-bay terminal (2026-10-04: the Eagleson vacuum has two
 * hoses, each with its own coin acceptor and timer board). Numbered, not
 * left/right: the terminal replaces one of the two original card readers, so
 * "left" would depend on where the customer stands -- the number is labelled
 * on the hose. [code] is what transactions.service_bay and the CMP
 * remote-start payload carry. Unit 1 is wired to PIN1 (Pulse 1, portNum 0),
 * unit 2 to PIN2 (Pulse 2, portNum 1).
 */
enum class Bay(val code: String, val number: Int, val defaultPort: Int) {
    ONE("1", 1, 0),
    TWO("2", 2, 1);

    /** "VACUUM 1" -- the product name from the merchant's packages plus the unit number. */
    fun title(productName: String) = "${productName.uppercase()} $number"

    companion object {
        fun fromCode(code: String?): Bay? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/**
 * Single-bay settings keep their original keys (existing terminals keep their
 * setup). Dual-bay sides have their own keys and are always coin pulses: the
 * relay circuit (PIN6/7) exists once, and level hold can't be extended.
 */
class OutputSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("timer_output", Context.MODE_PRIVATE)

    var dualBay: Boolean
        get() = prefs.getBoolean(KEY_DUAL, false)
        set(value) { prefs.edit().putBoolean(KEY_DUAL, value).apply() }

    fun load() = load("", OutputSettings())

    fun load(bay: Bay) = load(prefixOf(bay), OutputSettings(mode = OutputMode.COIN_PULSES, port = bay.defaultPort))
        .copy(mode = OutputMode.COIN_PULSES)

    fun save(s: OutputSettings) = save("", s)

    fun save(bay: Bay, s: OutputSettings) = save(prefixOf(bay), s.copy(mode = OutputMode.COIN_PULSES))

    private fun load(prefix: String, d: OutputSettings) = OutputSettings(
        mode = runCatching { OutputMode.valueOf(prefs.getString(prefix + KEY_MODE, null) ?: "") }.getOrDefault(d.mode),
        port = prefs.getInt(prefix + KEY_PORT, d.port),
        voltage = prefs.getInt(prefix + KEY_VOLTAGE, d.voltage),
        centsPerPulse = prefs.getInt(prefix + KEY_CENTS, d.centsPerPulse),
        pulseWidthMs = prefs.getLong(prefix + KEY_WIDTH, d.pulseWidthMs),
    )

    private fun save(prefix: String, s: OutputSettings) {
        prefs.edit()
            .putString(prefix + KEY_MODE, s.mode.name)
            .putInt(prefix + KEY_PORT, s.port)
            .putInt(prefix + KEY_VOLTAGE, s.voltage)
            .putInt(prefix + KEY_CENTS, s.centsPerPulse)
            .putLong(prefix + KEY_WIDTH, s.pulseWidthMs)
            .apply()
    }

    private fun prefixOf(bay: Bay) = "bay_${bay.code}_"

    private companion object {
        const val KEY_DUAL = "dual_bay"
        const val KEY_MODE = "mode"
        const val KEY_PORT = "port"
        const val KEY_VOLTAGE = "voltage"
        const val KEY_CENTS = "cents_per_pulse"
        const val KEY_WIDTH = "pulse_width_ms"
    }
}

/**
 * The customer session's output, following the technician's [OutputSettings]
 * at the moment each session starts.
 */
class ConfigurableOutput(
    private val gpio: IGpioProvider,
    private val settings: () -> OutputSettings,
) : TimerOutput {

    override suspend fun start(durationMs: Long, priceCents: Int): Boolean {
        val s = settings()
        return when (s.mode) {
            OutputMode.RELAY_HOLD -> gpio.holdRelayOutput(s.port, durationMs)
            OutputMode.LEVEL_HOLD -> gpio.triggerLogicPulse(s.port, s.voltage, durationMs, 100)
            OutputMode.COIN_PULSES -> {
                val n = s.pulsesFor(priceCents)
                Log.i(TAG, "Coin pulses: $n x ${s.pulseWidthMs}ms for $priceCents cents on port ${s.port}")
                // One pulse train at a time across both bays: each pulse is a
                // blocking SDK call on the same Digit IO board.
                pulseLock.withLock { (1..n).all { gpio.triggerLogicPulse(s.port, s.voltage, s.pulseWidthMs, s.pulseWidthMs) } }
            }
        }
    }

    /**
     * Session interrupted by a restart. Hold modes re-issue the remaining time;
     * coin pulses must NOT be sent again -- the machine is already timing the
     * credit it got, and resending would hand out free time.
     */
    override suspend fun resume(remainingMs: Long): Boolean = when (settings().mode) {
        OutputMode.COIN_PULSES -> true.also { Log.i(TAG, "Resume: coin-pulse mode, machine keeps its own time") }
        else -> start(remainingMs, 0)
    }

    override fun stop() {
        if (settings().mode == OutputMode.RELAY_HOLD) gpio.releaseHold(settings().port)
    }

    private companion object {
        const val TAG = "TimerOutput"
        val pulseLock = Mutex()
    }
}
